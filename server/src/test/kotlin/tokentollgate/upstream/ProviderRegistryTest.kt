package tokentollgate.upstream

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRegistryTest {

	private val environment = MockEnvironment().withProperty("TEST_KEY", "secret")

	private fun registry(vararg configs: ProviderConfig, upstream: UpstreamSettings = UpstreamSettings()) =
		ProviderRegistry(ProviderProperties(configs.toList(), upstream), environment)

	private val fake = ProviderConfig(name = "fake", type = ProviderType.OPENAI, baseUrl = "http://localhost:9090/", anonymous = true, models = listOf("fake-*"))
	private val fakeAnthropic = fake.copy(type = ProviderType.ANTHROPIC)
	private val openai = ProviderConfig(name = "openai", type = ProviderType.OPENAI, baseUrl = "https://api.openai.com", apiKeyEnv = "TEST_KEY", models = listOf("gpt-*", "o1*"))
	private val ollama = ProviderConfig(name = "ollama", type = ProviderType.OPENAI, baseUrl = "http://localhost:11434", anonymous = true, default = true)
	private val anthropic = ProviderConfig(name = "anthropic", type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", apiKeyEnv = "TEST_KEY", default = true, models = listOf("claude-*"))
	private val unset = ProviderConfig(name = "unset", type = ProviderType.OPENAI, baseUrl = "https://x", apiKeyEnv = "NOT_SET", models = listOf("x-*"))

	@Test
	fun `patterns match the whole model string including directives`() {
		val registry = registry(fake, openai, ollama)
		assertEquals("fake", (registry.route(ProviderType.OPENAI, "fake-small:in=120,out=8") as Route.To).provider.name)
		assertEquals("openai", (registry.route(ProviderType.OPENAI, "gpt-6-astra") as Route.To).provider.name)
		assertEquals("openai", (registry.route(ProviderType.OPENAI, "o1-mini") as Route.To).provider.name)
		assertEquals("ollama", (registry.route(ProviderType.OPENAI, "llama3.2") as Route.To).provider.name)
	}

	@Test
	fun `without a default provider an unmatched model is not found`() {
		assertIs<Route.NotFound>(registry(fake, openai).route(ProviderType.OPENAI, "llama3.2"))
		assertIs<Route.NotFound>(registry(fake).route(ProviderType.ANTHROPIC, "fake-small"))
	}

	@Test
	fun `a provider without its key is disabled and keeps its match`() {
		val registry = registry(unset, ollama)
		val route = registry.route(ProviderType.OPENAI, "x-1")
		assertIs<Route.Disabled>(route)
		assertEquals("unset", route.provider.name)
		assertFalse(route.provider.enabled)
		assertEquals(listOf("ollama"), registry.enabledProviders().map { it.name })
	}

	@Test
	fun `keys are read from the named variable, trailing slashes dropped, timeouts overridable`() {
		val slow = openai.copy(name = "slow", responseTimeout = Duration.ofMillis(500))
		val registry = registry(fake, slow, upstream = UpstreamSettings(responseTimeout = Duration.ofSeconds(7)))
		val fakeProvider = registry.providers.first { it.name == "fake" }
		assertNull(fakeProvider.apiKey)
		assertTrue(fakeProvider.enabled)
		assertEquals("http://localhost:9090", fakeProvider.baseUrl)
		assertEquals(Duration.ofSeconds(7), fakeProvider.settings.responseTimeout)
		val slowProvider = registry.providers.first { it.name == "slow" }
		assertEquals("secret", slowProvider.apiKey)
		assertEquals(Duration.ofMillis(500), slowProvider.settings.responseTimeout)
		assertEquals(Duration.ofSeconds(5), slowProvider.settings.connectTimeout)
		assertFalse(slowProvider.toString().contains("secret"))
		assertEquals(listOf("gpt-*", "o1*"), slowProvider.patterns.map { it.text })
		assertEquals(emptyList(), slowProvider.explicitModels)
	}

	@Test
	fun `the same name may be registered once per type and routes per type`() {
		val registry = registry(fake, fakeAnthropic, ollama, anthropic)
		val viaOpenAi = registry.route(ProviderType.OPENAI, "fake-small") as Route.To
		val viaAnthropic = registry.route(ProviderType.ANTHROPIC, "fake-small") as Route.To
		assertEquals("fake", viaOpenAi.provider.name)
		assertEquals("fake", viaAnthropic.provider.name)
		assertEquals(ProviderType.OPENAI, viaOpenAi.provider.type)
		assertEquals(ProviderType.ANTHROPIC, viaAnthropic.provider.type)
		assertEquals("anthropic", (registry.route(ProviderType.ANTHROPIC, "something-else") as Route.To).provider.name)
		assertEquals("ollama", (registry.route(ProviderType.OPENAI, "something-else") as Route.To).provider.name)
		assertEquals(2, registry.providers.count { it.name == "fake" })
	}

	@Test
	fun `a keyed provider takes its models with its own response timeout and never hands them to the default`() {
		val exaone = ProviderConfig(name = "exaone", type = ProviderType.OPENAI, baseUrl = "https://api.exaone.ai", apiKeyEnv = "TEST_KEY", models = listOf("k-exaone*"), responseTimeout = Duration.ofSeconds(300))

		val withKey = registry(fake, ollama, openai, exaone)
		val route = withKey.route(ProviderType.OPENAI, "k-exaone_v2.5") as Route.To
		assertEquals("exaone", route.provider.name)
		assertEquals(Duration.ofSeconds(300), route.provider.settings.responseTimeout)
		assertEquals(Duration.ofSeconds(60), route.provider.settings.streamIdleTimeout)
		assertEquals("ollama", (withKey.route(ProviderType.OPENAI, "qwen3:4b") as Route.To).provider.name)

		val withoutKey = registry(fake, ollama, exaone.copy(apiKeyEnv = "NOT_SET"))
		val disabled = withoutKey.route(ProviderType.OPENAI, "k-exaone_v2.5")
		assertIs<Route.Disabled>(disabled)
		assertEquals("exaone", disabled.provider.name)
		assertEquals("NOT_SET", disabled.provider.apiKeyEnv)
	}

	@Test
	fun `the same name and type twice is refused`() {
		assertThrows<IllegalArgumentException> { registry(fake, fake) }
		registry(fake, fakeAnthropic)
	}
}
