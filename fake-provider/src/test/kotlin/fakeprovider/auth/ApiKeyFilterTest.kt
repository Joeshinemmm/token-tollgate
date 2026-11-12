package fakeprovider.auth

import fakeprovider.anthropic.AnthropicFakeController
import fakeprovider.anthropicBody
import fakeprovider.error.FakeErrorHandler.Companion.REQUEST_ID_HEADER
import fakeprovider.openAiBody
import fakeprovider.openai.OpenAiFakeController
import fakeprovider.postJson
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

private const val KEY = "test-provider-key-0123456789"

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["fake.api-key=$KEY"])
@AutoConfigureWebTestClient
class ApiKeyFilterTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `requests without the key are refused in the protocol's shape`() {
		client.postJson(OpenAiFakeController.PATH, openAiBody("fake-small"))
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("authentication_error")
			.jsonPath("$.error.code").isEqualTo("invalid_api_key")
		client.postJson(AnthropicFakeController.PATH, anthropicBody("fake-small"))
			.exchange()
			.expectStatus().isUnauthorized
			.expectHeader().exists(REQUEST_ID_HEADER)
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("authentication_error")
	}

	@Test
	fun `a wrong key is refused`() {
		client.postJson(OpenAiFakeController.PATH, openAiBody("fake-small"))
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-the-key")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `the key passes as a Bearer token or as x-api-key`() {
		client.postJson(OpenAiFakeController.PATH, openAiBody("fake-small"))
			.header(HttpHeaders.AUTHORIZATION, "Bearer $KEY")
			.exchange()
			.expectStatus().isOk
		client.postJson(AnthropicFakeController.PATH, anthropicBody("fake-small"))
			.header(ApiKeyFilter.API_KEY_HEADER, KEY)
			.exchange()
			.expectStatus().isOk
	}

	@Test
	fun `health needs no key`() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk
	}
}
