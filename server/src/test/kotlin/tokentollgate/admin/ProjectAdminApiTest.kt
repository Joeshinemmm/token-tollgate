package tokentollgate.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import tokentollgate.createProject
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class ProjectAdminApiTest {

	@Autowired
	lateinit var client: WebTestClient

	@Test
	fun `create, read and list a project`() {
		val name = "project-${UUID.randomUUID()}"
		val created = client.createProject(name)
		assertEquals(name, created.name)
		assertNull(created.archivedAt)

		client.get().uri("/admin/v1/projects/${created.id}").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.id").isEqualTo(created.id.toString())
			.jsonPath("$.name").isEqualTo(name)
			.jsonPath("$.createdAt").value<String> { assertTrue(it.endsWith("Z"), "createdAt is ISO-8601 UTC: $it") }

		val listed = client.get().uri("/admin/v1/projects").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBodyList(ProjectResponse::class.java)
			.returnResult().responseBody!!
		assertTrue(listed.any { it.id == created.id })
	}

	@Test
	fun `names are trimmed and duplicates are rejected with 409`() {
		val name = "dup-${UUID.randomUUID()}"
		client.createProject("  $name  ")
		client.post().uri("/admin/v1/projects").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("name" to name))
			.exchange()
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("conflict")
	}

	@Test
	fun `blank or missing name is rejected with 400`() {
		client.post().uri("/admin/v1/projects").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("name" to "   "))
			.exchange()
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("bad_request")

		client.post().uri("/admin/v1/projects").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("nickname" to "x"))
			.exchange()
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("bad_request")
	}

	@Test
	fun `unknown or malformed id is 404 or 400`() {
		client.get().uri("/admin/v1/projects/${UUID.randomUUID()}").asAdmin()
			.exchange()
			.expectStatus().isNotFound
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("not_found")

		client.get().uri("/admin/v1/projects/not-a-uuid").asAdmin()
			.exchange()
			.expectStatus().isBadRequest
	}

	@Test
	fun `rename, archive and unarchive`() {
		val created = client.createProject()
		val newName = "renamed-${UUID.randomUUID()}"

		client.patch().uri("/admin/v1/projects/${created.id}").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("name" to newName, "archived" to true))
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.name").isEqualTo(newName)
			.jsonPath("$.archivedAt").isNotEmpty

		val active = client.get().uri("/admin/v1/projects").asAdmin()
			.exchange().expectStatus().isOk
			.expectBodyList(ProjectResponse::class.java).returnResult().responseBody!!
		assertTrue(active.none { it.id == created.id }, "archived project is hidden by default")

		val all = client.get().uri("/admin/v1/projects?includeArchived=true").asAdmin()
			.exchange().expectStatus().isOk
			.expectBodyList(ProjectResponse::class.java).returnResult().responseBody!!
		assertTrue(all.any { it.id == created.id }, "archived project is listed on request")

		client.patch().uri("/admin/v1/projects/${created.id}").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(mapOf("archived" to false))
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.archivedAt").isEmpty
	}
}
