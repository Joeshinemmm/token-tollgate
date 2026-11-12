package tokentollgate.relay.anthropic

import org.springframework.stereotype.Component
import tokentollgate.relay.InspectedRequest
import tokentollgate.relay.readRequestObject
import tokentollgate.relay.requireModel
import tools.jackson.databind.ObjectMapper

/**
 * Reads `model` and `stream` from a Messages request. The body goes upstream byte for byte: Anthropic streams
 * carry usage in `message_start` and `message_delta` without any request option, so there is nothing to inject.
 * `max_tokens` and the rest are the upstream's to validate.
 */
@Component
class AnthropicRequestInspector(private val mapper: ObjectMapper) {

	fun inspect(body: ByteArray): InspectedRequest {
		val root = readRequestObject(mapper, body)
		val model = requireModel(root, "model: Field required")
		return InspectedRequest(model, stream = root.path("stream").asBoolean(false), includeUsage = false, upstreamBody = body)
	}
}
