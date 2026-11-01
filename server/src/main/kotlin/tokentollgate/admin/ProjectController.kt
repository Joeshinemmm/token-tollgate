package tokentollgate.admin

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tokentollgate.keys.ProjectService
import tokentollgate.keys.VirtualKeyService
import java.util.UUID

@RestController
@RequestMapping("/admin/v1/projects")
class ProjectController(
	private val projects: ProjectService,
	private val keys: VirtualKeyService,
) {

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	suspend fun create(@RequestBody body: CreateProjectRequest): ProjectResponse =
		ProjectResponse.from(projects.create(body.name))

	@GetMapping
	suspend fun list(@RequestParam(defaultValue = "false") includeArchived: Boolean): List<ProjectResponse> =
		projects.list(includeArchived).map(ProjectResponse::from)

	@GetMapping("/{id}")
	suspend fun get(@PathVariable id: UUID): ProjectResponse =
		ProjectResponse.from(projects.get(id))

	@PatchMapping("/{id}")
	suspend fun update(@PathVariable id: UUID, @RequestBody body: UpdateProjectRequest): ProjectResponse =
		ProjectResponse.from(projects.update(id, body.name, body.archived))

	@PostMapping("/{id}/keys")
	@ResponseStatus(HttpStatus.CREATED)
	suspend fun issueKey(@PathVariable id: UUID, @RequestBody body: IssueKeyRequest): IssuedKeyResponse =
		IssuedKeyResponse.from(keys.issue(id, body.name))
}
