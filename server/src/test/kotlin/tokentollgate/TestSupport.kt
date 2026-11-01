package tokentollgate

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.admin.IssuedKeyResponse
import tokentollgate.admin.ProjectResponse
import java.util.UUID

fun <S : WebTestClient.RequestHeadersSpec<S>> S.asAdmin(): S =
	header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_ADMIN_TOKEN")

fun WebTestClient.createProject(name: String = "project-${UUID.randomUUID()}"): ProjectResponse =
	post().uri("/admin/v1/projects").asAdmin()
		.contentType(MediaType.APPLICATION_JSON)
		.bodyValue(mapOf("name" to name))
		.exchange()
		.expectStatus().isCreated
		.expectBody(ProjectResponse::class.java)
		.returnResult().responseBody!!

fun WebTestClient.issueKey(projectId: UUID, name: String = "default"): IssuedKeyResponse =
	post().uri("/admin/v1/projects/$projectId/keys").asAdmin()
		.contentType(MediaType.APPLICATION_JSON)
		.bodyValue(mapOf("name" to name))
		.exchange()
		.expectStatus().isCreated
		.expectBody(IssuedKeyResponse::class.java)
		.returnResult().responseBody!!
