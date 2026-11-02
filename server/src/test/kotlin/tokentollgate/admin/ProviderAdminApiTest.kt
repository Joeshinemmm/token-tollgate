package tokentollgate.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import kotlin.test.assertFalse

@IntegrationTest
class ProviderAdminApiTest {

	@Autowired lateinit var client: WebTestClient

	@Test
	fun `providers are listed without any key material`() {
		val body = client.get().uri("/admin/v1/providers").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$[?(@.name == 'fake')].enabled").isEqualTo(true)
			.jsonPath("$[?(@.name == 'fake')].type").isEqualTo("openai")
			.jsonPath("$[?(@.name == 'fake')].models[0]").isEqualTo("fake-*")
			.jsonPath("$[?(@.name == 'openai')].enabled").isEqualTo(false)
			.jsonPath("$[0].apiKey").doesNotExist()
			.jsonPath("$[0].apiKeyEnv").doesNotExist()
			.returnResult()
		val text = String(body.responseBody!!)
		assertFalse(text.contains("fake-upstream-key"), "key value leaked into the provider list")
		assertFalse(text.contains("FAKE_API_KEY"), "key variable name leaked into the provider list")
	}

	@Test
	fun `the list needs the admin token`() {
		client.get().uri("/admin/v1/providers").exchange().expectStatus().isUnauthorized
	}
}
