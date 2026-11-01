package tokentollgate.security

import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tokentollgate.gateway.ProtocolErrorWriter

/** `/admin/...` failures: `{"error":{"code":"unauthorized","message":...}}`. */
class AdminEntryPoint(private val writer: ProtocolErrorWriter) : ServerAuthenticationEntryPoint {
	override fun commence(exchange: ServerWebExchange, ex: AuthenticationException): Mono<Void> {
		val message = when (ex) {
			is BadCredentialsException -> "Invalid admin token"
			else -> "Missing admin token: send 'Authorization: Bearer <token>'"
		}
		val body = mapOf("error" to mapOf("code" to "unauthorized", "message" to message))
		return writer.writeJson(exchange, HttpStatus.UNAUTHORIZED, body)
	}
}

/** `/v1/...` failures, in the wire format of the protocol the client is speaking. */
class GatewayEntryPoint(private val writer: ProtocolErrorWriter) : ServerAuthenticationEntryPoint {
	override fun commence(exchange: ServerWebExchange, ex: AuthenticationException): Mono<Void> = when (ex) {
		is InvalidRequestException -> writer.write(
			exchange, HttpStatus.BAD_REQUEST, ex.message ?: "Invalid request",
			openAiType = "invalid_request_error", openAiCode = null, anthropicType = "invalid_request_error",
		)
		is BadCredentialsException, is DisabledException -> unauthorized(exchange, ex.message ?: "Invalid API key")
		is AuthenticationCredentialsNotFoundException ->
			unauthorized(exchange, "Missing API key: send 'Authorization: Bearer <key>' or '$API_KEY_HEADER: <key>'")
		else -> unauthorized(exchange, "Authentication failed")
	}

	private fun unauthorized(exchange: ServerWebExchange, message: String): Mono<Void> = writer.write(
		exchange, HttpStatus.UNAUTHORIZED, message,
		openAiType = "invalid_request_error", openAiCode = "invalid_api_key", anthropicType = "authentication_error",
	)
}
