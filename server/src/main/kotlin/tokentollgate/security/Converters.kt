package tokentollgate.security

import org.springframework.http.HttpHeaders
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

const val API_KEY_HEADER = "x-api-key"
const val TAG_HEADER = "X-Tollgate-Tag"
const val MAX_TAG_LENGTH = 100

/** Thrown while reading the request so that the entry point can answer 400 rather than 401. */
class InvalidRequestException(message: String) : AuthenticationException(message)

private const val BEARER_PREFIX = "Bearer "

private fun bearerToken(exchange: ServerWebExchange): String? {
	val header = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return null
	if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
	return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
}

/** `/admin/...`: `Authorization: Bearer <admin token>`. */
class AdminTokenConverter : ServerAuthenticationConverter {
	override fun convert(exchange: ServerWebExchange): Mono<Authentication> =
		Mono.justOrEmpty(bearerToken(exchange)?.let { AdminTokenCredentials(it) })
}

/** `/v1/...`: `Authorization: Bearer <key>` first, then `x-api-key: <key>`; optional `X-Tollgate-Tag`. */
class VirtualKeyConverter : ServerAuthenticationConverter {
	override fun convert(exchange: ServerWebExchange): Mono<Authentication> {
		val key = bearerToken(exchange)
			?: exchange.request.headers.getFirst(API_KEY_HEADER)?.trim()?.ifEmpty { null }
			?: return Mono.empty()
		val tag = exchange.request.headers.getFirst(TAG_HEADER)?.trim()?.ifEmpty { null }
		if (tag != null && tag.length > MAX_TAG_LENGTH) {
			return Mono.error(InvalidRequestException("$TAG_HEADER must be at most $MAX_TAG_LENGTH characters"))
		}
		return Mono.just(VirtualKeyCredentials(key, tag))
	}
}
