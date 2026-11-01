package fakeprovider.auth

import fakeprovider.FakeProperties
import fakeprovider.common.Ids
import fakeprovider.error.FakeErrorHandler.Companion.REQUEST_ID_HEADER
import fakeprovider.error.FakeErrors
import fakeprovider.error.Protocol
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * Optional provider-key check for paths under /v1: lets gateway tests prove that the virtual key was swapped for the
 * provider key. With no key configured every request passes. The presented value is never logged.
 */
@Component
class ApiKeyFilter(properties: FakeProperties, private val mapper: ObjectMapper) : WebFilter {

	private val expected: ByteArray? = properties.requiredApiKey()?.toByteArray(Charsets.UTF_8)

	override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
		val required = expected ?: return chain.filter(exchange)
		val path = exchange.request.path.value()
		if (!path.contains(PROTECTED_SEGMENT)) return chain.filter(exchange)
		val presented = presentedKey(exchange.request.headers)?.toByteArray(Charsets.UTF_8)
		if (presented != null && MessageDigest.isEqual(required, presented)) return chain.filter(exchange)
		return reject(exchange, Protocol.of(path))
	}

	private fun presentedKey(headers: HttpHeaders): String? {
		val authorization = headers.getFirst(HttpHeaders.AUTHORIZATION)
		if (authorization != null && authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) {
			return authorization.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
		}
		return headers.getFirst(API_KEY_HEADER)?.trim()?.ifEmpty { null }
	}

	private fun reject(exchange: ServerWebExchange, protocol: Protocol): Mono<Void> {
		val response = exchange.response
		val requestId = Ids.request()
		response.statusCode = HttpStatus.UNAUTHORIZED
		response.headers.contentType = MediaType.APPLICATION_JSON
		if (protocol == Protocol.ANTHROPIC) response.headers.set(REQUEST_ID_HEADER, requestId)
		val body = FakeErrors.body(protocol, HttpStatus.UNAUTHORIZED.value(), requestId = requestId)
		return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body))))
	}

	companion object {
		const val API_KEY_HEADER = "x-api-key"
		const val PROTECTED_SEGMENT = "/v1/"
		private const val BEARER_PREFIX = "Bearer "
	}
}
