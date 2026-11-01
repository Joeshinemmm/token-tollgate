package fakeprovider.auth

import fakeprovider.anthropicBody
import fakeprovider.openAiBody
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

private const val KEY = "test-provider-key-0123456789"

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["fake.api-key=$KEY"])
@AutoConfigureWebTestClient
class ApiKeyFilterTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `requests without the key are refused in the protocol's shape`() {
		client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue(openAiBody("fake-small"))
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("authentication_error")
			.jsonPath("$.error.code").isEqualTo("invalid_api_key")
		client.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(anthropicBody("fake-small"))
			.exchange()
			.expectStatus().isUnauthorized
			.expectHeader().exists("request-id")
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("authentication_error")
	}

	@Test
	fun `a wrong key is refused`() {
		client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-the-key")
			.bodyValue(openAiBody("fake-small"))
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `the key passes as a Bearer token or as x-api-key`() {
		client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.AUTHORIZATION, "Bearer $KEY")
			.bodyValue(openAiBody("fake-small"))
			.exchange()
			.expectStatus().isOk
		client.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
			.header(ApiKeyFilter.API_KEY_HEADER, KEY)
			.bodyValue(anthropicBody("fake-small"))
			.exchange()
			.expectStatus().isOk
	}

	@Test
	fun `health needs no key`() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk
	}
}
