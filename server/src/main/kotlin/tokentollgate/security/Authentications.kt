package tokentollgate.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

const val ROLE_ADMIN = "ROLE_ADMIN"
const val ROLE_GATEWAY_KEY = "ROLE_GATEWAY_KEY"

/** What the gateway knows about the caller once a virtual key has been accepted. */
data class AuthenticatedKey(
	val keyId: UUID,
	val projectId: UUID,
	val keyPrefix: String,
	val tag: String?,
)

/** Unauthenticated: a bearer token presented to `/admin/...`. */
class AdminTokenCredentials(private val token: String) : AbstractAuthenticationToken(null) {
	override fun getCredentials(): String = token
	override fun getPrincipal(): Any? = null
}

/** Authenticated admin. */
class AdminAuthentication : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(ROLE_ADMIN))) {
	init {
		isAuthenticated = true
	}

	override fun getCredentials(): Any? = null
	override fun getPrincipal(): String = "admin"
}

/** Unauthenticated: a virtual key presented to `/v1/...`, plus the optional request tag. */
class VirtualKeyCredentials(private val plaintextKey: String, val tag: String?) : AbstractAuthenticationToken(null) {
	override fun getCredentials(): String = plaintextKey
	override fun getPrincipal(): Any? = null
}

/** Authenticated gateway caller. The principal is the [AuthenticatedKey]. */
class VirtualKeyAuthentication(private val key: AuthenticatedKey) :
	AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(ROLE_GATEWAY_KEY))) {
	init {
		isAuthenticated = true
	}

	override fun getCredentials(): Any? = null
	override fun getPrincipal(): AuthenticatedKey = key
}
