package tokentollgate.upstream

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** The wire protocol an upstream speaks. OpenAI-compatible servers such as Ollama and the fake provider are OPENAI. */
enum class ProviderType { OPENAI, ANTHROPIC }

/** `tollgate.providers` and `tollgate.upstream` from application.yml. Keys are named by environment variable, never held here. */
@ConfigurationProperties("tollgate")
data class ProviderProperties(
	val providers: List<ProviderConfig> = emptyList(),
	val upstream: UpstreamSettings = UpstreamSettings(),
)

data class UpstreamSettings(
	val connectTimeout: Duration = Duration.ofSeconds(5),
	/** Time allowed until the upstream response headers arrive. */
	val responseTimeout: Duration = Duration.ofSeconds(60),
	/** Longest gap allowed between two stream frames. */
	val streamIdleTimeout: Duration = Duration.ofSeconds(60),
	/** Largest plain (non-streamed) upstream response the gateway buffers before relaying it. */
	val maxInMemorySize: DataSize = DataSize.ofMegabytes(16),
)

data class ProviderConfig(
	val name: String,
	val type: ProviderType,
	val baseUrl: String,
	/** Name of the environment variable (or property) holding the API key. */
	val apiKeyEnv: String? = null,
	/** Usable without a key, such as a local Ollama or the fake provider. */
	val anonymous: Boolean = false,
	/** Receives models that match no pattern of any provider of the same type. */
	val default: Boolean = false,
	/** Model name patterns; `*` is the only wildcard and matches any run of characters. */
	val models: List<String> = emptyList(),
	val connectTimeout: Duration? = null,
	val responseTimeout: Duration? = null,
	val streamIdleTimeout: Duration? = null,
)
