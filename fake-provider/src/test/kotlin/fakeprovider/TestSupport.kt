package fakeprovider

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration

fun openAiBody(
	model: String,
	stream: Boolean = false,
	includeUsage: Boolean? = null,
	maxTokens: Int? = null,
): Map<String, Any?> = buildMap {
	put("model", model)
	put("messages", listOf(mapOf("role" to "user", "content" to "hello")))
	if (stream) put("stream", true)
	if (includeUsage != null) put("stream_options", mapOf("include_usage" to includeUsage))
	if (maxTokens != null) put("max_tokens", maxTokens)
}

fun anthropicBody(model: String, stream: Boolean = false, maxTokens: Int? = 1024): Map<String, Any?> = buildMap {
	put("model", model)
	put("messages", listOf(mapOf("role" to "user", "content" to "hello")))
	if (stream) put("stream", true)
	if (maxTokens != null) put("max_tokens", maxTokens)
}

/** Everything a raw SSE exchange returned: the bytes as text, the status, and the error that ended it, if any. */
data class SseResult(val status: HttpStatusCode?, val contentType: MediaType?, val raw: String, val error: Throwable?) {
	/** Frames as sent: each is its lines without the terminating blank line. */
	val frames: List<String> get() = raw.split("\n\n").filter { it.isNotEmpty() }
}

fun WebTestClient.postJson(path: String, body: Any): WebTestClient.RequestHeadersSpec<*> =
	post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body)

fun String.sseData(): String = lines().first { it.startsWith("data: ") }.removePrefix("data: ")

fun String.sseEvent(): String? = lines().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ")

fun ObjectMapper.json(frame: String): JsonNode = readTree(frame.sseData())

/**
 * Reads the response as raw bytes so that the exact wire format (event lines, `data: ` spacing, `[DONE]`) can be
 * asserted, and keeps what arrived before a connection was cut.
 */
fun readSse(port: Int, path: String, body: Map<String, Any?>): SseResult {
	var status: HttpStatusCode? = null
	var contentType: MediaType? = null
	var error: Throwable? = null
	val raw = StringBuilder()
	WebClient.create("http://localhost:$port")
		.post().uri(path)
		.contentType(MediaType.APPLICATION_JSON)
		.bodyValue(body)
		.exchangeToFlux { response ->
			status = response.statusCode()
			contentType = response.headers().contentType().orElse(null)
			response.bodyToFlux(DataBuffer::class.java)
		}
		.doOnNext { buffer ->
			raw.append(buffer.toString(Charsets.UTF_8))
			DataBufferUtils.release(buffer)
		}
		.onErrorResume { e ->
			error = e
			Mono.empty()
		}
		.blockLast(Duration.ofSeconds(30))
	return SseResult(status, contentType, raw.toString(), error)
}
