package fakeprovider

import org.springframework.boot.context.properties.ConfigurationProperties

/** Bound from `fake.*` in fake-provider.yml, which maps the `FAKE_*` environment variables. */
@ConfigurationProperties("fake")
data class FakeProperties(
	/** When non-blank, every request under /v1 must present this value as `Authorization: Bearer` or `x-api-key`. */
	val apiKey: String? = null,
	/** Path the endpoints are mounted under when embedded elsewhere (the gateway's tests use "/fake"); empty when standalone. */
	val pathPrefix: String = "",
	val defaults: Defaults = Defaults(),
) {
	/** Values used when the model name carries no directive for them. */
	data class Defaults(
		val inputTokens: Int = 100,
		val cachedInputTokens: Int = 0,
		val outputTokens: Int = 50,
		val ttftMs: Long = 0,
		val chunkIntervalMs: Long = 0,
		/** Probability (0..1) that a request fails with 500 regardless of its directive. */
		val errorRate: Double = 0.0,
	)

	fun requiredApiKey(): String? = apiKey?.trim()?.ifEmpty { null }
}
