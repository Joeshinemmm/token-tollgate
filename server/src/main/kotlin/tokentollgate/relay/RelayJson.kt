package tokentollgate.relay

import org.springframework.http.HttpStatusCode
import tokentollgate.pricing.TokenUsage
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

internal fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null

/** Parses a request body that must be a JSON object, or throws [InvalidRelayRequestException]. */
internal fun readRequestObject(mapper: ObjectMapper, body: ByteArray): JsonNode {
	val root = try {
		mapper.readTree(body)
	} catch (e: JacksonException) {
		throw InvalidRelayRequestException("Request body must be valid JSON")
	}
	if (root == null || !root.isObject) throw InvalidRelayRequestException("Request body must be a JSON object")
	return root
}

/** The trimmed `model` of a request, or [InvalidRelayRequestException] with [missingMessage] when it is empty. */
internal fun requireModel(root: JsonNode, missingMessage: String): String {
	val model = root.path("model").stringOrNull()?.trim() ?: ""
	if (model.isEmpty()) throw InvalidRelayRequestException(missingMessage, param = "model")
	return model
}

/** Reads the served model plus usage (2xx) or `error.type` (otherwise) from a buffered upstream body. */
internal fun parsePlainOutcome(
	mapper: ObjectMapper,
	status: HttpStatusCode,
	body: ByteArray,
	parseUsage: (JsonNode) -> TokenUsage?,
): PlainOutcome {
	val node = try {
		mapper.readTree(body)
	} catch (e: JacksonException) {
		null
	}
	if (node == null || !node.isObject) return PlainOutcome(null, null, null)
	val model = node.path("model").stringOrNull()
	if (status.is2xxSuccessful) return PlainOutcome(model, parseUsage(node.path("usage")), null)
	val errorType = node.path("error").path("type").stringOrNull()
	return PlainOutcome(model, null, errorType)
}
