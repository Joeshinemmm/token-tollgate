package fakeprovider

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = ["fake.defaults.error-rate=1"])
@AutoConfigureWebTestClient
class ErrorRateTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `an error rate of 1 fails every request with 500 in each protocol's shape`() {
		client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue(openAiBody("fake-small"))
			.exchange()
			.expectStatus().is5xxServerError
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("server_error")
		client.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(anthropicBody("fake-small"))
			.exchange()
			.expectStatus().is5xxServerError
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("api_error")
	}
}
