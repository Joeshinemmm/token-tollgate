package tokentollgate

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tokentollgate.db.ModelPrice
import tokentollgate.db.ModelPriceRepository
import tokentollgate.db.UsageRecord
import tokentollgate.db.UsageRecordRepository
import tokentollgate.relay.HeaderRules
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

fun <S : WebTestClient.RequestHeadersSpec<S>> S.withKey(key: String): S =
	header(HttpHeaders.AUTHORIZATION, "Bearer $key")

fun chatBody(model: String, stream: Boolean = false, includeUsage: Boolean? = null, maxTokens: Int? = null): Map<String, Any?> = buildMap {
	put("model", model)
	put("messages", listOf(mapOf("role" to "user", "content" to "hello")))
	if (stream) put("stream", true)
	if (includeUsage != null) put("stream_options", mapOf("include_usage" to includeUsage))
	if (maxTokens != null) put("max_tokens", maxTokens)
}

/** Test prices for the fake provider; inserted once per database since the context is shared. */
suspend fun ModelPriceRepository.ensureFakePrices() {
	val effectiveFrom = Instant.parse("2026-01-01T00:00:00Z")
	val rows = listOf(
		ModelPrice(provider = "fake", model = "fake-relay", inputPerMtok = BigDecimal("1.00"), outputPerMtok = BigDecimal("2.00"), cachedInputPerMtok = BigDecimal("0.10"), effectiveFrom = effectiveFrom),
		ModelPrice(provider = "fake", model = "fake-large", inputPerMtok = BigDecimal("10.00"), outputPerMtok = BigDecimal("20.00"), cachedInputPerMtok = BigDecimal("1.00"), effectiveFrom = effectiveFrom),
	)
	for (row in rows) {
		if (findByProviderAndModelAndEffectiveFrom(row.provider, row.model, row.effectiveFrom) == null) save(row)
	}
}

/** The record is written off the response path, so wait for it. */
suspend fun UsageRecordRepository.awaitRecord(requestId: UUID, timeoutMs: Long = 5_000): UsageRecord = withTimeout(timeoutMs) {
	var record = findByRequestId(requestId)
	while (record == null) {
		delay(50)
		record = findByRequestId(requestId)
	}
	record
}

fun requestIdOf(result: WebTestClient.BodyContentSpec): UUID =
	UUID.fromString(result.returnResult().responseHeaders.getFirst(HeaderRules.REQUEST_ID_HEADER))

/** A raw SSE exchange: status, headers, the bytes as received, and the error that ended it, if any. */
data class SseResult(val status: HttpStatusCode?, val headers: HttpHeaders?, val raw: String, val error: Throwable?) {
	val frames: List<String> get() = raw.split("\n\n").filter { it.isNotEmpty() }
	val requestId: UUID get() = UUID.fromString(headers!!.getFirst(HeaderRules.REQUEST_ID_HEADER))
}

fun String.sseData(): String = lines().first { it.startsWith("data: ") }.removePrefix("data: ")

fun ObjectMapper.json(frame: String): JsonNode = readTree(frame.sseData())

/**
 * Reads the gateway's streamed response as raw bytes so that frames and the terminating `[DONE]` can be checked
 * exactly, keeping whatever arrived before the connection was cut. [takeFrames] cancels after that many buffers.
 */
fun readSse(port: Int, path: String, key: String, body: Map<String, Any?>, takeFrames: Long? = null): SseResult {
	var status: HttpStatusCode? = null
	var headers: HttpHeaders? = null
	var error: Throwable? = null
	val raw = StringBuilder()
	var flux = WebClient.create("http://localhost:$port")
		.post().uri(path)
		.contentType(MediaType.APPLICATION_JSON)
		.header(HttpHeaders.AUTHORIZATION, "Bearer $key")
		.bodyValue(body)
		.exchangeToFlux { response ->
			status = response.statusCode()
			headers = response.headers().asHttpHeaders()
			response.bodyToFlux(DataBuffer::class.java)
		}
		.doOnNext { buffer ->
			raw.append(buffer.toString(Charsets.UTF_8))
			DataBufferUtils.release(buffer)
		}
	if (takeFrames != null) flux = flux.take(takeFrames)
	flux.onErrorResume { e ->
		error = e
		Mono.empty()
	}.blockLast(Duration.ofSeconds(30))
	return SseResult(status, headers, raw.toString(), error)
}

fun messagesBody(model: String, stream: Boolean = false, maxTokens: Int? = 64): Map<String, Any?> = buildMap {
	put("model", model)
	put("messages", listOf(mapOf("role" to "user", "content" to "hello")))
	if (stream) put("stream", true)
	if (maxTokens != null) put("max_tokens", maxTokens)
}

fun String.sseEvent(): String? = lines().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ")
