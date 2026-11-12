package fakeprovider.error

/** Which wire format the client is speaking, decided by the path so that it also works under a mount prefix. */
enum class Protocol {
	OPENAI, ANTHROPIC;

	companion object {
		fun of(path: String): Protocol = if (path.endsWith(ANTHROPIC_PATH)) ANTHROPIC else OPENAI
		const val ANTHROPIC_PATH = "/v1/messages"
	}
}

/** A request the fake refuses with 400 in the caller's protocol; [param] names the offending field for OpenAI. */
class InvalidRequestException(message: String, val param: String? = null) : RuntimeException(message)

/**
 * Error bodies in the shape of each provider. Anthropic's status-to-type table is documented in full;
 * OpenAI documents the body keys and only some type/code values, the rest are our own (see dev-log 005).
 */
object FakeErrors {

	fun body(protocol: Protocol, status: Int, message: String? = null, param: String? = null, requestId: String): Map<String, Any?> =
		when (protocol) {
			Protocol.OPENAI -> openAi(status, message ?: openAiMessage(status), param)
			Protocol.ANTHROPIC -> anthropic(status, message ?: anthropicMessage(status), requestId)
		}

	fun openAi(status: Int, message: String, param: String? = null): Map<String, Any?> {
		val (type, code) = openAiType(status)
		return mapOf("error" to linkedMapOf("message" to message, "type" to type, "param" to param, "code" to code))
	}

	fun anthropic(status: Int, message: String, requestId: String): Map<String, Any?> =
		linkedMapOf("type" to "error", "error" to linkedMapOf("type" to anthropicType(status), "message" to message), "request_id" to requestId)

	/** Documented: 400, 429 (type only), 503. Our own: 401, 403, 404, other 4xx and 5xx. */
	fun openAiType(status: Int): Pair<String, String?> = when (status) {
		400 -> "invalid_request_error" to null
		401 -> "authentication_error" to "invalid_api_key"
		403 -> "permission_error" to null
		404 -> "not_found_error" to null
		429 -> "rate_limit_error" to null
		503 -> "service_unavailable_error" to "server_is_overloaded"
		in 400..499 -> "invalid_request_error" to null
		else -> "server_error" to null
	}

	/** The full table from the Anthropic errors page; other 4xx use invalid_request_error as that page allows. */
	fun anthropicType(status: Int): String = when (status) {
		400 -> "invalid_request_error"
		401 -> "authentication_error"
		402 -> "billing_error"
		403 -> "permission_error"
		404 -> "not_found_error"
		409 -> "conflict_error"
		413 -> "request_too_large"
		429 -> "rate_limit_error"
		500 -> "api_error"
		504 -> "timeout_error"
		529 -> "overloaded_error"
		in 400..499 -> "invalid_request_error"
		else -> "api_error"
	}

	fun openAiMessage(status: Int): String = when (status) {
		401 -> "Incorrect API key provided"
		429 -> "Rate limit reached for requests"
		500 -> "The server had an error while processing your request"
		503 -> "Model temporarily overloaded"
		else -> "Simulated error (status $status)"
	}

	fun anthropicMessage(status: Int): String = when (status) {
		401 -> "invalid x-api-key"
		429 -> "This request would exceed your organization's rate limit"
		500 -> "Internal server error"
		529 -> "Overloaded"
		else -> "Simulated error (status $status)"
	}
}
