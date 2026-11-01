package fakeprovider.anthropic

import fakeprovider.common.Ids
import fakeprovider.common.RandomFailures
import fakeprovider.common.ResponseWriter
import fakeprovider.common.StreamAbortedException
import fakeprovider.common.streamFrames
import fakeprovider.directive.Directive
import fakeprovider.directive.DirectiveParser
import fakeprovider.error.FakeErrorHandler.Companion.REQUEST_ID_HEADER
import fakeprovider.error.FakeErrors
import fakeprovider.error.InvalidRequestException
import fakeprovider.text.FakeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode

/** The parts of a Messages request the fake acts on. `max_tokens` is required, as in the real API. */
data class AnthropicRequest(val model: String, val stream: Boolean, val maxTokens: Int) {

	companion object {
		fun from(body: JsonNode): AnthropicRequest {
			if (!body.isObject) throw InvalidRequestException("Request body must be a JSON object")
			val model = body.path("model").let { if (it.isString) it.stringValue().trim() else "" }
			if (model.isEmpty()) throw InvalidRequestException("model: Field required")
			val messages = body.path("messages")
			if (!messages.isArray || messages.size() == 0) throw InvalidRequestException("messages: Field required")
			val maxTokens = body.path("max_tokens")
			if (!maxTokens.isIntegralNumber || maxTokens.intValue() < 1) throw InvalidRequestException("max_tokens: Field required")
			return AnthropicRequest(model = model, stream = body.path("stream").asBoolean(false), maxTokens = maxTokens.intValue())
		}
	}
}

@RestController
class AnthropicFakeController(
	private val parser: DirectiveParser,
	private val failures: RandomFailures,
	private val writer: ResponseWriter,
) {

	private val log = LoggerFactory.getLogger(AnthropicFakeController::class.java)

	@PostMapping(PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun messages(@RequestBody body: JsonNode, exchange: ServerWebExchange): Mono<Void> = mono<Void> {
		val request = AnthropicRequest.from(body)
		val directive = parser.parse(request.model)
		val response = exchange.response
		val requestId = Ids.request()
		try {
			val status = when {
				failures.hit() -> fail(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), directive, requestId)
				directive.errorStatus != null -> fail(response, directive.errorStatus, directive, requestId)
				request.stream -> stream(response, request, directive, requestId)
				else -> complete(response, request, directive, requestId)
			}
			log.info("POST {} model={} stream={} status={}", PATH, request.model, request.stream, status)
		} catch (e: StreamAbortedException) {
			log.info("POST {} model={} stream=true aborted after {} chunks", PATH, request.model, directive.abortAfter)
			throw e
		}
		null
	}

	private suspend fun fail(response: ServerHttpResponse, status: Int, directive: Directive, requestId: String): Int {
		if (directive.ttftMs > 0) delay(directive.ttftMs)
		val body = FakeErrors.anthropic(status, FakeErrors.anthropicMessage(status), requestId)
		writer.json(response, HttpStatusCode.valueOf(status), body, headers(requestId))
		return status
	}

	private suspend fun complete(response: ServerHttpResponse, request: AnthropicRequest, directive: Directive, requestId: String): Int {
		if (directive.ttftMs > 0) delay(directive.ttftMs)
		val (outputTokens, stopReason) = outcome(request, directive)
		val body = AnthropicShapes.message(
			id = Ids.message(),
			model = directive.model,
			text = FakeText.content(outputTokens),
			stopReason = stopReason,
			usage = AnthropicShapes.usage(directive.inputTokens, directive.cachedInputTokens, outputTokens),
		)
		writer.json(response, HttpStatus.OK, body, headers(requestId))
		return HttpStatus.OK.value()
	}

	private suspend fun stream(response: ServerHttpResponse, request: AnthropicRequest, directive: Directive, requestId: String): Int {
		val (outputTokens, stopReason) = outcome(request, directive)
		val id = Ids.message()
		fun event(type: String, data: Map<String, Any?>): String = AnthropicShapes.event(type, writer.toJson(data))

		val startUsage = AnthropicShapes.usage(directive.inputTokens, directive.cachedInputTokens, outputTokens = 1)
		val head = listOf(
			event("message_start", AnthropicShapes.messageStart(id, directive.model, startUsage)),
			event("content_block_start", AnthropicShapes.contentBlockStart()),
			event("ping", AnthropicShapes.ping()),
		)
		val tail = listOf(
			event("content_block_stop", AnthropicShapes.contentBlockStop()),
			event("message_delta", AnthropicShapes.messageDelta(stopReason, outputTokens)),
			event("message_stop", AnthropicShapes.messageStop()),
		)
		val frames = streamFrames(
			directive = directive,
			outputTokens = outputTokens,
			head = head,
			contentFrame = { index -> event("content_block_delta", AnthropicShapes.contentBlockDelta(FakeText.chunk(index))) },
			tail = tail,
			errorFrame = event("error", AnthropicShapes.overloadedError()),
		)
		writer.stream(response, frames, headers(requestId))
		return HttpStatus.OK.value()
	}

	/** `max_tokens` below the directive's output caps the text and ends with `max_tokens`, as the real API would. */
	private fun outcome(request: AnthropicRequest, directive: Directive): Pair<Int, String> =
		if (request.maxTokens < directive.outputTokens) request.maxTokens to "max_tokens" else directive.outputTokens to "end_turn"

	private fun headers(requestId: String): Map<String, String> = mapOf(REQUEST_ID_HEADER to requestId)

	companion object {
		const val PATH = "/v1/messages"
	}
}
