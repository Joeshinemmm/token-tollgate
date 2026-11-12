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
import tokentollgate.relay.parsePlainOutcome
import tokentollgate.upstream.ProviderType
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Chat Completions: reads `usage` from plain bodies, injects and later hides the usage chunk of streams. */
@Component
class OpenAiAdapter(private val inspector: OpenAiRequestInspector, private val mapper: ObjectMapper) : ProtocolAdapter {

	override val protocol: Protocol = Protocol.OPENAI
	override val providerType: ProviderType = ProviderType.OPENAI

	override fun inspect(body: ByteArray): InspectedRequest = inspector.inspect(body)

	override fun prepareUpstreamHeaders(headers: HttpHeaders) = Unit

	override fun parsePlain(status: HttpStatusCode, body: ByteArray): PlainOutcome =
		parsePlainOutcome(mapper, status, body, OpenAiUsageParser::parse)

	override fun streamObserver(inspected: InspectedRequest): StreamObserver =
		OpenAiStreamObserver(mapper, forwardUsageChunk = inspected.includeUsage)

	override fun errorBody(error: GatewayError, requestId: UUID): Map<String, Any?> = error.openAiBody()

	override fun errorFrame(error: GatewayError, requestId: UUID): ByteArray =
		"data: ${mapper.writeValueAsString(error.openAiBody())}\n\n".toByteArray(Charsets.UTF_8)
}
