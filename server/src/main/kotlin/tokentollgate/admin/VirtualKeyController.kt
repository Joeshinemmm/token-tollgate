package tokentollgate.admin

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tokentollgate.keys.VirtualKeyService
import java.util.UUID

@RestController
@RequestMapping("/admin/v1/keys")
class VirtualKeyController(private val keys: VirtualKeyService) {

	@GetMapping
	suspend fun list(@RequestParam(required = false) projectId: UUID?): List<VirtualKeyResponse> =
		keys.list(projectId).map(VirtualKeyResponse::from)

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	suspend fun revoke(@PathVariable id: UUID) {
		keys.revoke(id)
	}
}
