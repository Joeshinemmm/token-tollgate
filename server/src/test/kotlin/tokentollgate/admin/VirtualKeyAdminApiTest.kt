package tokentollgate.admin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import tokentollgate.createProject
import tokentollgate.db.VirtualKeyRepository
import tokentollgate.issueKey
import tokentollgate.keys.KeyGenerator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class VirtualKeyAdminApiTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var keys: VirtualKeyRepository

	@Test
	fun `issuing returns the plaintext once and stores only its hash`() = runBlocking {
		val project = client.createProject()
		val issued = client.issueKey(project.id, "ci")

		assertTrue(issued.key.startsWith("tt_"), issued.key)
		assertEquals(35, issued.key.length)
		assertEquals(issued.key.take(8), issued.keyPrefix)
		assertEquals("ci", issued.name)

		val stored = assertNotNull(keys.findById(issued.id))
		assertEquals(KeyGenerator.hash(issued.key), stored.keyHash)
		assertNull(stored.revokedAt)

		client.get().uri("/admin/v1/keys?projectId=${project.id}").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1)
			.jsonPath("$[0].id").isEqualTo(issued.id.toString())
			.jsonPath("$[0].keyPrefix").isEqualTo(issued.keyPrefix)
			.jsonPath("$[0].key").doesNotExist()
			.jsonPath("$[0].keyHash").doesNotExist()
	}

	@Test
	fun `revoking is idempotent and shows up in the list`() {
		val project = client.createProject()
		val issued = client.issueKey(project.id)

		client.delete().uri("/admin/v1/keys/${issued.id}").asAdmin().exchange().expectStatus().isNoContent
		client.delete().uri("/admin/v1/keys/${issued.id}").asAdmin().exchange().expectStatus().isNoContent

		client.get().uri("/admin/v1/keys?projectId=${project.id}").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$[0].revokedAt").isNotEmpty
	}

	@Test
	fun `revoking an unknown key is 404`() {
		client.delete().uri("/admin/v1/keys/${UUID.randomUUID()}").asAdmin()
			.exchange()
			.expectStatus().isNotFound
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("not_found")
	}

	@Test
	fun `keys cannot be issued for an unknown or archived project`() {
		client.post().uri("/admin/v1/projects/${UUID.randomUUID()}/keys").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("name" to "x"))
			.exchange()
			.expectStatus().isNotFound

		val project = client.createProject()
		client.patch().uri("/admin/v1/projects/${project.id}").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("archived" to true))
			.exchange()
			.expectStatus().isOk

		client.post().uri("/admin/v1/projects/${project.id}/keys").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("name" to "x"))
			.exchange()
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("conflict")
	}
}
