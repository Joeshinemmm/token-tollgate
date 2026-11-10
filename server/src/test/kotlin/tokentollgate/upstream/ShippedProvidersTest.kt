package tokentollgate.upstream

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Binds the provider list of the application.yml that ships in the jar. The integration tests replace that list
 * wholesale with fake upstreams, so nothing else would notice a typo or a shadowed pattern in the shipped one.
 */
class ShippedProvidersTest {

	private fun registry(vararg keys: String): ProviderRegistry {
		val environment = MockEnvironment()
		keys.forEach { environment.setProperty(it, "test-key") }
		YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).forEach { environment.propertySources.addLast(it) }
		val properties = Binder.get(environment).bind("tollgate", Bindable.of(ProviderProperties::class.java)).get()
		return ProviderRegistry(properties, environment)
	}

	private fun providerOf(route: Route): Provider = when (route) {
		is Route.To -> route.provider
		is Route.Disabled -> route.provider
		Route.NotFound -> error("no provider matched")
	}

	@Test
	fun `each model family reaches its provider`() {
		val registry = registry()
		assertEquals("fake", providerOf(registry.route(ProviderType.OPENAI, "fake-small:in=120,out=8")).name)
		assertEquals("fake", providerOf(registry.route(ProviderType.ANTHROPIC, "fake-small")).name)
		assertEquals("openai", providerOf(registry.route(ProviderType.OPENAI, "gpt-6-astra")).name)
		assertEquals("exaone", providerOf(registry.route(ProviderType.OPENAI, "k-exaone_v2.5")).name)
		assertEquals("anthropic", providerOf(registry.route(ProviderType.ANTHROPIC, "claude-haiku-5-5")).name)
		assertEquals("ollama", providerOf(registry.route(ProviderType.OPENAI, "qwen3:4b")).name)
		assertEquals("anthropic", providerOf(registry.route(ProviderType.ANTHROPIC, "something-else")).name)
	}

	@Test
	fun `exaone is keyed by EXAONE_API_KEY and waits longer for a response than the others`() {
		val withKey = registry("EXAONE_API_KEY")
		val route = withKey.route(ProviderType.OPENAI, "k-exaone_v2.5")
		assertIs<Route.To>(route)
		val exaone = route.provider
		assertEquals("https://api.exaone.ai", exaone.baseUrl)
		assertEquals("EXAONE_API_KEY", exaone.apiKeyEnv)
		assertFalse(exaone.anonymous)
		assertFalse(exaone.default)
		assertEquals(Duration.ofSeconds(300), exaone.settings.responseTimeout)
		assertEquals(Duration.ofSeconds(60), exaone.settings.streamIdleTimeout)

		val ollama = providerOf(withKey.route(ProviderType.OPENAI, "qwen3:4b"))
		assertEquals(Duration.ofSeconds(60), ollama.settings.responseTimeout)
	}

	@Test
	fun `without keys the keyed providers are disabled and the keyless ones still work`() {
		val registry = registry()
		assertIs<Route.Disabled>(registry.route(ProviderType.OPENAI, "k-exaone_v2.5"))
		assertIs<Route.Disabled>(registry.route(ProviderType.OPENAI, "gpt-6-astra"))
		assertIs<Route.Disabled>(registry.route(ProviderType.ANTHROPIC, "claude-haiku-5-5"))
		assertIs<Route.To>(registry.route(ProviderType.OPENAI, "fake-small"))
		assertIs<Route.To>(registry.route(ProviderType.OPENAI, "qwen3:4b"))
		assertTrue(registry.enabledProviders().none { it.name == "exaone" })
	}
}
