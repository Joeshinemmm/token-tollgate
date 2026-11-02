package tokentollgate.admin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tokentollgate.upstream.ProviderRegistry

@RestController
@RequestMapping("/admin/v1/providers")
class ProviderController(private val registry: ProviderRegistry) {

	@GetMapping
	fun list(): List<ProviderResponse> = registry.providers.map(ProviderResponse::from)
}
