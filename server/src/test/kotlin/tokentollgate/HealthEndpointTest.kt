package tokentollgate

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

@IntegrationTest
class HealthEndpointTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `health endpoint is public and reports UP including the database`() {
		client.get().uri("/actuator/health")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP")
			.jsonPath("$.components.r2dbc.status").isEqualTo("UP")
	}

	@Test
	fun `any other unmatched path is denied by default`() {
		client.get().uri("/nope")
			.exchange()
			.expectStatus().isUnauthorized
	}
}
