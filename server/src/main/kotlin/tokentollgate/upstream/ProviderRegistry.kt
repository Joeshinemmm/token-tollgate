package tokentollgate.upstream

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** A model name pattern from configuration: literal text with `*` wildcards. */
class ModelPattern(val text: String) {
	private val regex = Regex(text.split('*').joinToString(".*") { Regex.escape(it) })

	/** True when the pattern has no wildcard and therefore names one model. */
	val isExact: Boolean = !text.contains('*')

	fun matches(model: String): Boolean = regex.matches(model)
}

/** A configured upstream with its key already resolved. [toString] never includes the key. */
class Provider(
	val name: String,
	val type: ProviderType,
	val baseUrl: String,
	val apiKey: String?,
	val apiKeyEnv: String?,
	val anonymous: Boolean,
	val default: Boolean,
	val patterns: List<ModelPattern>,
	val settings: UpstreamSettings,
) {
	val enabled: Boolean = anonymous || apiKey != null

	/** Configured model names without wildcards, listed by `GET /v1/models`. */
	val explicitModels: List<String> get() = patterns.filter { it.isExact }.map { it.text }

	override fun toString(): String = "Provider(name=$name, type=$type, baseUrl=$baseUrl, enabled=$enabled)"
}

sealed class Route {
	data class To(val provider: Provider) : Route()
	data class Disabled(val provider: Provider) : Route()
	data object NotFound : Route()
}

/**
 * Resolves the configured providers once at startup: reads each key from its environment variable, decides
 * which providers are enabled, and builds one [WebClient] per provider with that provider's timeouts.
 * The builder is created here rather than injected: Spring Boot's webflux starter configures the server side only.
 */
@Component
class ProviderRegistry(properties: ProviderProperties, environment: Environment) {

	private val log = LoggerFactory.getLogger(ProviderRegistry::class.java)

	val providers: List<Provider> = properties.providers.map { config ->
		val apiKey = config.apiKeyEnv?.let { environment.getProperty(it) }?.trim()?.ifEmpty { null }
		Provider(
			name = config.name,
			type = config.type,
			baseUrl = config.baseUrl.trimEnd('/'),
			apiKey = apiKey,
			apiKeyEnv = config.apiKeyEnv,
			anonymous = config.anonymous,
			default = config.default,
			patterns = config.models.map(::ModelPattern),
			settings = UpstreamSettings(
				connectTimeout = config.connectTimeout ?: properties.upstream.connectTimeout,
				responseTimeout = config.responseTimeout ?: properties.upstream.responseTimeout,
				streamIdleTimeout = config.streamIdleTimeout ?: properties.upstream.streamIdleTimeout,
				maxInMemorySize = properties.upstream.maxInMemorySize,
			),
		)
	}

	private val clients: Map<String, WebClient>

	init {
		val duplicate = providers.groupBy { it.name }.filterValues { it.size > 1 }.keys
		require(duplicate.isEmpty()) { "Duplicate provider names in tollgate.providers: $duplicate" }
		clients = providers.associate { provider -> provider.name to client(provider.baseUrl, provider.settings) }
		providers.forEach { provider ->
			if (provider.enabled) {
				log.info("provider {} enabled: type={} baseUrl={} key={}", provider.name, provider.type, provider.baseUrl, if (provider.apiKey != null) "set" else "none")
			} else {
				log.warn("provider {} disabled: environment variable {} is empty", provider.name, provider.apiKeyEnv)
			}
		}
	}

	/** First provider of [type] whose pattern matches [model], else the default one, else nothing. */
	fun route(type: ProviderType, model: String): Route {
		val matched = providers.firstOrNull { it.type == type && it.patterns.any { pattern -> pattern.matches(model) } }
			?: providers.firstOrNull { it.type == type && it.default }
			?: return Route.NotFound
		return if (matched.enabled) Route.To(matched) else Route.Disabled(matched)
	}

	fun client(provider: Provider): WebClient = clients.getValue(provider.name)

	fun enabledProviders(): List<Provider> = providers.filter { it.enabled }

	private fun client(baseUrl: String, settings: UpstreamSettings): WebClient {
		val httpClient = HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.connectTimeout.toMillis().toInt())
			.responseTimeout(settings.responseTimeout)
		return WebClient.builder()
			.baseUrl(baseUrl)
			.clientConnector(ReactorClientHttpConnector(httpClient))
			.codecs { it.defaultCodecs().maxInMemorySize(settings.maxInMemorySize.toBytes().toInt()) }
			.build()
	}
}
