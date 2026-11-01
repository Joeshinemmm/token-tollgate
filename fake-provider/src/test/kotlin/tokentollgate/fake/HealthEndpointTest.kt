package tokentollgate.fake

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HealthEndpointTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `health endpoint reports UP`() {
		client.get().uri("/actuator/health")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP")
	}
}
