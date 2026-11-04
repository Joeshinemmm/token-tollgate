package tokentollgate.relay.anthropic

import org.springframework.stereotype.Component
import tokentollgate.relay.InspectedRequest
import tokentollgate.relay.InvalidRelayRequestException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Reads `model` and `stream` from a Messages request. The body goes upstream byte for byte: Anthropic streams
 * carry usage in `message_start` and `message_delta` without any request option, so there is nothing to inject.
 * `max_tokens` and the rest are the upstream's to validate.
 */
@Component
class AnthropicRequestInspector(private val mapper: ObjectMapper) {

	fun inspect(body: ByteArray): InspectedRequest {
		val root = try {
			mapper.readTree(body)
		} catch (e: JacksonException) {
			throw InvalidRelayRequestException("Request body must be valid JSON")
		}
		if (root == null || !root.isObject) throw InvalidRelayRequestException("Request body must be a JSON object")
		val model = root.path("model").let { if (it.isString) it.stringValue().trim() else "" }
		if (model.isEmpty()) throw InvalidRelayRequestException("model: Field required", param = "model")
		return InspectedRequest(model, stream = root.path("stream").asBoolean(false), includeUsage = false, upstreamBody = body)
	}
}
