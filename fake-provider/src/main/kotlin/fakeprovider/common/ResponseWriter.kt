package fakeprovider.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

/** Writes responses straight to the reactive response so that stream frames go out, and flush, one at a time. */
@Component
class ResponseWriter(private val mapper: ObjectMapper) {

	fun toJson(value: Any): String = mapper.writeValueAsString(value)

	suspend fun json(response: ServerHttpResponse, status: HttpStatusCode, body: Any, headers: Map<String, String> = emptyMap()) {
		response.statusCode = status
		response.headers.contentType = MediaType.APPLICATION_JSON
		headers.forEach { (name, value) -> response.headers.set(name, value) }
		val bytes = mapper.writeValueAsBytes(body)
		response.writeWith(Mono.just(response.bufferFactory().wrap(bytes))).awaitSingleOrNull()
	}

	/** Each element of [frames] is one complete SSE frame (its lines plus the blank line) and is flushed on its own. */
	suspend fun stream(response: ServerHttpResponse, frames: Flow<String>, headers: Map<String, String> = emptyMap()) {
		response.statusCode = HttpStatus.OK
		response.headers.contentType = MediaType(MediaType.TEXT_EVENT_STREAM, Charsets.UTF_8)
		response.headers.set(HttpHeaders.CACHE_CONTROL, "no-cache")
		headers.forEach { (name, value) -> response.headers.set(name, value) }
		val factory = response.bufferFactory()
		val publishers: Flow<Publisher<DataBuffer>> = frames.map { frame -> Mono.just(factory.wrap(frame.toByteArray(Charsets.UTF_8))) }
		response.writeAndFlushWith(publishers.asFlux()).awaitSingleOrNull()
	}
}
