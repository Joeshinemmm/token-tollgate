package tokentollgate.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `tollgate.admin.token`, which Spring Boot's relaxed binding also reads from the
 * `TOLLGATE_ADMIN_TOKEN` environment variable. There is deliberately no default: the server
 * refuses to start without a token, and a short token is rejected as well.
 */
@ConfigurationProperties("tollgate.admin")
data class AdminProperties(val token: String = "") {
	init {
		require(token.isNotBlank()) { "TOLLGATE_ADMIN_TOKEN must be set" }
		require(token.length >= MIN_TOKEN_LENGTH) { "TOLLGATE_ADMIN_TOKEN must be at least $MIN_TOKEN_LENGTH characters" }
	}

	companion object {
		const val MIN_TOKEN_LENGTH = 16
	}
}
