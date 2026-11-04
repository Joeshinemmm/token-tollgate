package tokentollgate.relay

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import tokentollgate.db.Protocol
import tokentollgate.pricing.TokenUsage
import tokentollgate.upstream.ProviderType
import java.util.UUID

class InvalidRelayRequestException(message: String, val param: String? = null) : RuntimeException(message)

/** What the engine needs from a request body, plus the body to send upstream (possibly adjusted by the adapter). */
data class InspectedRequest(
	val model: String,
	val stream: Boolean,
	/** Whether the client itself asked for a usage chunk; only meaningful for OpenAI streams. */
	val includeUsage: Boolean,
	val upstreamBody: ByteArray,
)

/** What a buffered (non-streamed) upstream response told us. */
data class PlainOutcome(val modelServed: String?, val usage: TokenUsage?, val errorType: String?)

/** Watches one streamed response frame by frame and decides which frames reach the client. */
interface StreamObserver {
	/** Returns whether [frame] should be forwarded. */
	fun observe(frame: SseFrame): Boolean
	val model: String?
	val usage: TokenUsage?
	val done: Boolean
	val sawError: Boolean
	val errorType: String?
	val firstContentNanos: Long?
}

/**
 * Everything the relay engine needs to know about one wire protocol: how to read a request, what to add to the
 * upstream headers, how to read usage from a plain response or a stream, and how to shape gateway errors.
 */
interface ProtocolAdapter {
	val protocol: Protocol
	val providerType: ProviderType
	fun inspect(body: ByteArray): InspectedRequest
	fun prepareUpstreamHeaders(headers: HttpHeaders)
	fun parsePlain(status: HttpStatusCode, body: ByteArray): PlainOutcome
	fun streamObserver(inspected: InspectedRequest): StreamObserver
	fun errorBody(error: GatewayError, requestId: UUID): Map<String, Any?>
	fun errorFrame(error: GatewayError, requestId: UUID): ByteArray
}
