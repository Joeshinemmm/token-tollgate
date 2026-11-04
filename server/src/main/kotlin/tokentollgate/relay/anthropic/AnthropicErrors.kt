package tokentollgate.relay.anthropic

import org.springframework.http.HttpStatus
import tokentollgate.relay.GatewayError
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Gateway errors in Anthropic's `{"type": "error", "error": {"type", "message"}, "request_id"}` shape.
 * The status → type table is the documented one; 502 and 503 are not in it and use `api_error`.
 * Anthropic errors carry no `code`, so the gateway's reason code goes into the message.
 */
object AnthropicErrors {

	fun typeFor(status: HttpStatus): String = when (status.value()) {
		400 -> "invalid_request_error"
		401 -> "authentication_error"
		403 -> "permission_error"
		404 -> "not_found_error"
		429 -> "rate_limit_error"
		504 -> "timeout_error"
		529 -> "overloaded_error"
		in 400..499 -> "invalid_request_error"
		else -> "api_error"
	}

	fun body(error: GatewayError, requestId: UUID?): Map<String, Any?> {
		val body = linkedMapOf<String, Any?>(
			"type" to "error",
			"error" to linkedMapOf<String, Any?>("type" to typeFor(error.status), "message" to message(error)),
		)
		if (requestId != null) body["request_id"] = requestId.toString()
		return body
	}

	/** The documented mid-stream shape: `event: error` with the error object and no request id. */
	fun event(mapper: ObjectMapper, error: GatewayError): ByteArray =
		"event: error\ndata: ${mapper.writeValueAsString(body(error, requestId = null))}\n\n".toByteArray(Charsets.UTF_8)

	private fun message(error: GatewayError): String = if (error.code != null) "${error.message} (${error.code})" else error.message
}
