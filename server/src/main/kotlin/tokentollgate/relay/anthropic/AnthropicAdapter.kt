package tokentollgate.relay.anthropic

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

/**
 * Messages API: bodies pass through unchanged, usage is normalized from Anthropic's three input counts, and
 * `anthropic-version` is added only when the client did not send one (the docs disagree on whether it is required).
 */
@Component
class AnthropicAdapter(private val inspector: AnthropicRequestInspector, private val mapper: ObjectMapper) : ProtocolAdapter {

	override val protocol: Protocol = Protocol.ANTHROPIC
	override val providerType: ProviderType = ProviderType.ANTHROPIC

	override fun inspect(body: ByteArray): InspectedRequest = inspector.inspect(body)

	override fun prepareUpstreamHeaders(headers: HttpHeaders) {
		if (headers.getFirst(VERSION_HEADER).isNullOrBlank()) headers.set(VERSION_HEADER, DEFAULT_VERSION)
	}

	override fun parsePlain(status: HttpStatusCode, body: ByteArray): PlainOutcome =
		parsePlainOutcome(mapper, status, body, AnthropicUsageParser::parse)

	override fun streamObserver(inspected: InspectedRequest): StreamObserver = AnthropicStreamObserver(mapper)

	override fun errorBody(error: GatewayError, requestId: UUID): Map<String, Any?> = AnthropicErrors.body(error, requestId)

	override fun errorFrame(error: GatewayError, requestId: UUID): ByteArray = AnthropicErrors.event(mapper, error)

	companion object {
		const val VERSION_HEADER = "anthropic-version"
		/** The value the official streaming and errors pages use in their curl examples. */
		const val DEFAULT_VERSION = "2023-06-01"
	}
}
