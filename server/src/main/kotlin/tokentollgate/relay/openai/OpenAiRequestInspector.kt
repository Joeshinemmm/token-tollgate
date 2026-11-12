package tokentollgate.relay.openai

import org.springframework.stereotype.Component
import tokentollgate.relay.InspectedRequest
import tokentollgate.relay.readRequestObject
import tokentollgate.relay.requireModel
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Reads `model`, `stream` and `stream_options.include_usage` from the request body. For streaming requests that
 * did not ask for usage, the upstream body gets `include_usage: true` so that the gateway can read the usage chunk
 * (and drop it again before it reaches the client). Any other request body goes upstream byte for byte.
 */
@Component
class OpenAiRequestInspector(private val mapper: ObjectMapper) {

	fun inspect(body: ByteArray): InspectedRequest {
		val root = readRequestObject(mapper, body)
		val model = requireModel(root, "you must provide a model parameter")
		val stream = root.path("stream").asBoolean(false)
		val includeUsage = root.path("stream_options").path("include_usage").asBoolean(false)
		if (!stream || includeUsage) return InspectedRequest(model, stream, includeUsage, body)

		val options = root.path("stream_options")
		val target = if (options is ObjectNode) options else (root as ObjectNode).putObject("stream_options")
		target.put("include_usage", true)
		return InspectedRequest(model, stream = true, includeUsage = false, upstreamBody = mapper.writeValueAsBytes(root))
	}
}
