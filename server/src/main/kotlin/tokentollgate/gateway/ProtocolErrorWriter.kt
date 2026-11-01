package tokentollgate.gateway

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

/**
 * Writes gateway errors in the wire format of the protocol the client is speaking:
 * Anthropic's `{"type":"error","error":{...}}` under `/v1/messages`, OpenAI's `{"error":{...}}` elsewhere.
 * The shapes were checked against the official references (see dev-log 000, section 3).
 */
@Component
class ProtocolErrorWriter(private val mapper: ObjectMapper) {

	fun isAnthropic(exchange: ServerWebExchange): Boolean =
		exchange.request.path.value().startsWith(ANTHROPIC_PATH)

	fun write(
		exchange: ServerWebExchange,
		status: HttpStatus,
		message: String,
		openAiType: String,
		openAiCode: String?,
		anthropicType: String,
	): Mono<Void> {
		val body: Map<String, Any?> = if (isAnthropic(exchange)) {
			mapOf("type" to "error", "error" to mapOf("type" to anthropicType, "message" to message))
		} else {
			mapOf("error" to mapOf("message" to message, "type" to openAiType, "param" to null, "code" to openAiCode))
		}
		return writeJson(exchange, status, body)
	}

	fun writeJson(exchange: ServerWebExchange, status: HttpStatus, body: Any): Mono<Void> {
		val response = exchange.response
		response.statusCode = status
		response.headers.contentType = MediaType.APPLICATION_JSON
		val bytes = mapper.writeValueAsBytes(body)
		return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
	}

	companion object {
		const val ANTHROPIC_PATH = "/v1/messages"
	}
}
