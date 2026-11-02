package tokentollgate.relay.openai

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class InvalidRelayRequestException(message: String, val param: String? = null) : RuntimeException(message)

/** What the gateway needs from a Chat Completions request, plus the body to send upstream. */
data class InspectedRequest(
	val model: String,
	val stream: Boolean,
	/** Whether the client asked for the usage chunk itself; the upstream request always asks for it when streaming. */
	val includeUsage: Boolean,
	val upstreamBody: ByteArray,
)

/**
 * Reads `model`, `stream` and `stream_options.include_usage` from the request body. For streaming requests that
 * did not ask for usage, the upstream body gets `include_usage: true` so that the gateway can read the usage chunk
 * (and drop it again before it reaches the client). Any other request body goes upstream byte for byte.
 */
@Component
class OpenAiRequestInspector(private val mapper: ObjectMapper) {

	fun inspect(body: ByteArray): InspectedRequest {
		val root = try {
			mapper.readTree(body)
		} catch (e: JacksonException) {
			throw InvalidRelayRequestException("Request body must be valid JSON")
		}
		if (root == null || !root.isObject) throw InvalidRelayRequestException("Request body must be a JSON object")
		val model = root.path("model").let { if (it.isString) it.stringValue().trim() else "" }
		if (model.isEmpty()) throw InvalidRelayRequestException("you must provide a model parameter", param = "model")
		val stream = root.path("stream").asBoolean(false)
		val includeUsage = root.path("stream_options").path("include_usage").asBoolean(false)
		if (!stream || includeUsage) return InspectedRequest(model, stream, includeUsage, body)

		val options = root.path("stream_options")
		val target = if (options is ObjectNode) options else (root as ObjectNode).putObject("stream_options")
		target.put("include_usage", true)
		return InspectedRequest(model, stream = true, includeUsage = false, upstreamBody = mapper.writeValueAsBytes(root))
	}
}
