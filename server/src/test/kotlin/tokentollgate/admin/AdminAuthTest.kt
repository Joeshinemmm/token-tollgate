package tokentollgate.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin

@IntegrationTest
class AdminAuthTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `missing token is rejected with the admin error shape`() {
		client.get().uri("/admin/v1/projects")
			.exchange()
			.expectStatus().isUnauthorized
			.expectHeader().contentTypeCompatibleWith("application/json")
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("unauthorized")
			.jsonPath("$.error.message").value<String> { assert(it.contains("Missing admin token")) }
	}

	@Test
	fun `wrong token is rejected without echoing it`() {
		client.get().uri("/admin/v1/projects")
			.header(HttpHeaders.AUTHORIZATION, "Bearer definitely-not-the-token")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("unauthorized")
			.jsonPath("$.error.message").isEqualTo("Invalid admin token")
	}

	@Test
	fun `basic auth or a bare token is treated as missing`() {
		client.get().uri("/admin/v1/projects")
			.header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
			.exchange()
			.expectStatus().isUnauthorized
	}

	@Test
	fun `correct token is accepted`() {
		client.get().uri("/admin/v1/projects").asAdmin()
			.exchange()
			.expectStatus().isOk
	}
}
