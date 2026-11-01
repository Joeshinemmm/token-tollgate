package tokentollgate.security

import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tokentollgate.keys.KeyAuthResult
import tokentollgate.keys.KeyRejection
import tokentollgate.keys.VirtualKeyService
import java.security.MessageDigest

/**
 * The single [ReactiveAuthenticationManager] bean. It dispatches on the credential type so that
 * both security chains share one manager and Spring Boot does not generate a default user.
 */
@Component
class TollgateAuthenticationManager(
	private val adminProperties: AdminProperties,
	private val keyService: VirtualKeyService,
) : ReactiveAuthenticationManager {

	override fun authenticate(authentication: Authentication): Mono<Authentication> = when (authentication) {
		is AdminTokenCredentials -> authenticateAdmin(authentication)
		is VirtualKeyCredentials -> authenticateKey(authentication)
		else -> Mono.error(BadCredentialsException("Unsupported authentication"))
	}

	private fun authenticateAdmin(credentials: AdminTokenCredentials): Mono<Authentication> {
		val expected = adminProperties.token.toByteArray(Charsets.UTF_8)
		val presented = credentials.credentials.toByteArray(Charsets.UTF_8)
		return if (MessageDigest.isEqual(expected, presented)) {
			Mono.just(AdminAuthentication())
		} else {
			Mono.error(BadCredentialsException("Invalid admin token"))
		}
	}

	private fun authenticateKey(credentials: VirtualKeyCredentials): Mono<Authentication> = mono {
		when (val result = keyService.authenticate(credentials.credentials)) {
			is KeyAuthResult.Accepted -> {
				keyService.recordUse(result.view.keyId)
				VirtualKeyAuthentication(
					AuthenticatedKey(
						keyId = result.view.keyId,
						projectId = result.view.projectId,
						keyPrefix = result.view.keyPrefix,
						tag = credentials.tag,
					),
				)
			}
			is KeyAuthResult.Rejected -> throw when (result.reason) {
				KeyRejection.ProjectArchived -> DisabledException(result.reason.message)
				else -> BadCredentialsException(result.reason.message)
			}
		}
	}
}
