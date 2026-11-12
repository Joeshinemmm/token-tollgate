package tokentollgate.security

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Test-only route under `/v1` that echoes what the gateway chain authenticated. It lets auth tests probe the chain without relaying to a provider. */
@RestController
class AuthProbeController {

	@GetMapping("/v1/auth-probe")
	suspend fun probe(@AuthenticationPrincipal key: AuthenticatedKey): AuthenticatedKey = key
}
