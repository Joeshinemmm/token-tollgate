package tokentollgate.relay.openai

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import tokentollgate.db.Protocol
import tokentollgate.relay.GatewayError
import tokentollgate.relay.InspectedRequest
import tokentollgate.relay.PlainOutcome
import tokentollgate.relay.ProtocolAdapter
import tokentollgate.relay.StreamObserver
import tokentollgate.upstream.ProviderType
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Chat Completions: reads `usage` from plain bodies, injects and later hides the usage chunk of streams. */
@Component
class OpenAiAdapter(private val inspector: OpenAiRequestInspector, private val mapper: ObjectMapper) : ProtocolAdapter {

	override val protocol: Protocol = Protocol.OPENAI
	override val providerType: ProviderType = ProviderType.OPENAI

	override fun inspect(body: ByteArray): InspectedRequest = inspector.inspect(body)

	override fun prepareUpstreamHeaders(headers: HttpHeaders) = Unit

	override fun parsePlain(status: HttpStatusCode, body: ByteArray): PlainOutcome {
		val node = try {
			mapper.readTree(body)
		} catch (e: JacksonException) {
			null
		}
		if (node == null || !node.isObject) return PlainOutcome(null, null, null)
		val model = node.path("model").let { if (it.isString) it.stringValue() else null }
		if (status.is2xxSuccessful) return PlainOutcome(model, OpenAiUsageParser.parse(node.path("usage")), null)
		val errorType = node.path("error").path("type").let { if (it.isString) it.stringValue() else null }
		return PlainOutcome(model, null, errorType)
	}

	override fun streamObserver(inspected: InspectedRequest): StreamObserver =
		OpenAiStreamObserver(mapper, forwardUsageChunk = inspected.includeUsage)

	override fun errorBody(error: GatewayError, requestId: UUID): Map<String, Any?> = error.openAiBody()

	override fun errorFrame(error: GatewayError, requestId: UUID): ByteArray =
		"data: ${mapper.writeValueAsString(error.openAiBody())}\n\n".toByteArray(Charsets.UTF_8)
}
