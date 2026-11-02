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
	private val openai = ProviderConfig(name = "openai", type = ProviderType.OPENAI, baseUrl = "https://api.openai.com", apiKeyEnv = "TEST_KEY", models = listOf("gpt-*", "o1*"))
	private val ollama = ProviderConfig(name = "ollama", type = ProviderType.OPENAI, baseUrl = "http://localhost:11434", anonymous = true, default = true)
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
	fun `duplicate names are refused`() {
		assertThrows<IllegalArgumentException> { registry(fake, fake) }
	}
}
