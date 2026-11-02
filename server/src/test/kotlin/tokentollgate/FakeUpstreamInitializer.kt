package tokentollgate

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import java.net.ServerSocket

/**
 * Picks one free port before the context starts and points both the embedded server and the test provider list
 * at it, so that the gateway under test calls the fake provider mounted on its own port under `/fake`.
 * Registered through `@ContextConfiguration(initializers = ...)` because `server.port` must be known before the
 * web server is created, which is earlier than any bean-based property registration runs.
 */
class FakeUpstreamInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

	override fun initialize(context: ConfigurableApplicationContext) {
		val base = "http://localhost:$PORT/fake"
		val properties = linkedMapOf<String, Any>(
			"server.port" to PORT,
			"fake.api-key" to FAKE_KEY,
			"fake.path-prefix" to "/fake",
			"FAKE_API_KEY" to FAKE_KEY,
			"tollgate.upstream.response-timeout" to "10s",
			"tollgate.upstream.stream-idle-timeout" to "10s",
		)
		provider(properties, 0, name = "fake", base = base, apiKeyEnv = "FAKE_API_KEY", anonymous = true, models = "fake-*")
		provider(properties, 1, name = "fake-nokey", base = base, apiKeyEnv = null, anonymous = true, models = "nokey-*")
		provider(properties, 2, name = "fake-slow", base = base, apiKeyEnv = "FAKE_API_KEY", anonymous = true, models = "slow-*")
		properties["tollgate.providers[2].response-timeout"] = "500ms"
		properties["tollgate.providers[2].stream-idle-timeout"] = "500ms"
		provider(properties, 3, name = "openai", base = "https://api.openai.com", apiKeyEnv = "TOLLGATE_TEST_KEY_THAT_IS_NEVER_SET", anonymous = false, models = "gpt-*")
		context.environment.propertySources.addFirst(MapPropertySource("fakeUpstream", properties))
	}

	private fun provider(into: MutableMap<String, Any>, index: Int, name: String, base: String, apiKeyEnv: String?, anonymous: Boolean, models: String) {
		into["tollgate.providers[$index].name"] = name
		into["tollgate.providers[$index].type"] = "openai"
		into["tollgate.providers[$index].base-url"] = base
		if (apiKeyEnv != null) into["tollgate.providers[$index].api-key-env"] = apiKeyEnv
		into["tollgate.providers[$index].anonymous"] = anonymous
		into["tollgate.providers[$index].models[0]"] = models
	}

	companion object {
		/** One port for the whole test JVM; every integration test shares the cached context on it. */
		val PORT: Int by lazy { ServerSocket(0).use { it.localPort } }
		const val FAKE_KEY = "fake-upstream-key-0123456789abcdef"
	}
}
