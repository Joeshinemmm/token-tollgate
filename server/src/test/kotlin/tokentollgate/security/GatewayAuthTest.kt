package tokentollgate.security

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import tokentollgate.createProject
import tokentollgate.db.VirtualKeyRepository
import tokentollgate.issueKey
import kotlin.test.assertNotNull

@IntegrationTest
class GatewayAuthTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var keys: VirtualKeyRepository

	@Test
	fun `missing key on an OpenAI-style path answers in OpenAI error shape`() {
		client.get().uri("/v1/auth-probe")
			.exchange()
			.expectStatus().isUnauthorized
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.code").isEqualTo("invalid_api_key")
			.jsonPath("$.error.param").isEmpty
			.jsonPath("$.error.message").value<String> { assert(it.contains("Missing API key")) }
	}

	@Test
	fun `missing key on the Anthropic path answers in Anthropic error shape`() {
		client.post().uri("/v1/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("model" to "x"))
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("authentication_error")
			.jsonPath("$.error.message").value<String> { assert(it.contains("Missing API key")) }
	}

	@Test
	fun `unknown key is rejected without echoing it`() {
		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer tt_this_key_does_not_exist_0000000000")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.message").isEqualTo("Invalid API key")
	}

	@Test
	fun `valid key is accepted via Bearer and via x-api-key, and the tag is attached`() {
		val project = client.createProject()
		val issued = client.issueKey(project.id)

		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer ${issued.key}")
			.header(TAG_HEADER, "nightly-build")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.keyId").isEqualTo(issued.id.toString())
			.jsonPath("$.projectId").isEqualTo(project.id.toString())
			.jsonPath("$.keyPrefix").isEqualTo(issued.keyPrefix)
			.jsonPath("$.tag").isEqualTo("nightly-build")

		client.get().uri("/v1/auth-probe")
			.header(API_KEY_HEADER, issued.key)
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.keyId").isEqualTo(issued.id.toString())
			.jsonPath("$.tag").isEmpty
	}

	@Test
	fun `an over-long tag is a 400 in the protocol shape`() {
		val project = client.createProject()
		val issued = client.issueKey(project.id)

		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer ${issued.key}")
			.header(TAG_HEADER, "x".repeat(MAX_TAG_LENGTH + 1))
			.exchange()
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.code").isEmpty
	}

	@Test
	fun `revoked key and archived project are rejected`() {
		val project = client.createProject()
		val revoked = client.issueKey(project.id, "revoked")
		val active = client.issueKey(project.id, "active")

		client.delete().uri("/admin/v1/keys/${revoked.id}").asAdmin().exchange().expectStatus().isNoContent
		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer ${revoked.key}")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.message").isEqualTo("API key has been revoked")

		client.patch().uri("/admin/v1/projects/${project.id}").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("archived" to true))
			.exchange()
			.expectStatus().isOk
		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer ${active.key}")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.message").isEqualTo("Project is archived")
	}

	@Test
	fun `first use records last_used_at without blocking the request`() = runBlocking<Unit> {
		val project = client.createProject()
		val issued = client.issueKey(project.id)

		client.get().uri("/v1/auth-probe")
			.header(HttpHeaders.AUTHORIZATION, "Bearer ${issued.key}")
			.exchange()
			.expectStatus().isOk

		withTimeout(5_000) {
			while (keys.findById(issued.id)?.lastUsedAt == null) delay(50)
		}
		assertNotNull(keys.findById(issued.id)?.lastUsedAt)
	}
}
