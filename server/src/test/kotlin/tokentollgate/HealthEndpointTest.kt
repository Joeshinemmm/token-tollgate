package tokentollgate

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration::class)
class HealthEndpointTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `health endpoint reports UP including the database`() {
		client.get().uri("/actuator/health")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP")
			.jsonPath("$.components.r2dbc.status").isEqualTo("UP")
	}
}
