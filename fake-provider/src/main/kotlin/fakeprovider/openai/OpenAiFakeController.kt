package fakeprovider.openai

import fakeprovider.common.Ids
import fakeprovider.common.RandomFailures
import fakeprovider.common.ResponseWriter
import fakeprovider.common.StreamAbortedException
import fakeprovider.common.streamFrames
import fakeprovider.directive.Directive
import fakeprovider.directive.DirectiveParser
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
import java.time.Instant

/** The parts of a Chat Completions request the fake acts on; everything else is accepted and ignored. */
data class OpenAiRequest(val model: String, val stream: Boolean, val includeUsage: Boolean, val maxTokens: Int?) {

	companion object {
		fun from(body: JsonNode): OpenAiRequest {
			if (!body.isObject) throw InvalidRequestException("Request body must be a JSON object")
			val model = body.path("model").let { if (it.isString) it.stringValue().trim() else "" }
			if (model.isEmpty()) throw InvalidRequestException("you must provide a model parameter", param = "model")
			val messages = body.path("messages")
			if (!messages.isArray || messages.size() == 0) throw InvalidRequestException("messages must be a non-empty array", param = "messages")
			val maxTokens = sequenceOf("max_completion_tokens", "max_tokens")
				.map { body.path(it) }
				.firstOrNull { it.isIntegralNumber }
				?.intValue()
			return OpenAiRequest(
				model = model,
				stream = body.path("stream").asBoolean(false),
				includeUsage = body.path("stream_options").path("include_usage").asBoolean(false),
				maxTokens = maxTokens,
			)
		}
	}
}

@RestController
class OpenAiFakeController(
	private val parser: DirectiveParser,
	private val failures: RandomFailures,
	private val writer: ResponseWriter,
) {

	private val log = LoggerFactory.getLogger(OpenAiFakeController::class.java)

	@PostMapping(PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
	fun chatCompletions(@RequestBody body: JsonNode, exchange: ServerWebExchange): Mono<Void> = mono<Void> {
		val request = OpenAiRequest.from(body)
		val directive = parser.parse(request.model)
		val response = exchange.response
		try {
			val status = when {
				failures.hit() -> fail(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), directive)
				directive.errorStatus != null -> fail(response, directive.errorStatus, directive)
				request.stream -> stream(response, request, directive)
				else -> complete(response, request, directive)
			}
			log.info("POST {} model={} stream={} status={}", PATH, request.model, request.stream, status)
		} catch (e: StreamAbortedException) {
			log.info("POST {} model={} stream=true aborted after {} chunks", PATH, request.model, directive.abortAfter)
			throw e
		}
		null
	}

	private suspend fun fail(response: ServerHttpResponse, status: Int, directive: Directive): Int {
		if (directive.ttftMs > 0) delay(directive.ttftMs)
		writer.json(response, HttpStatusCode.valueOf(status), FakeErrors.openAi(status, FakeErrors.openAiMessage(status)))
		return status
	}

	private suspend fun complete(response: ServerHttpResponse, request: OpenAiRequest, directive: Directive): Int {
		if (directive.ttftMs > 0) delay(directive.ttftMs)
		val (outputTokens, finishReason) = outcome(request, directive)
		val body = OpenAiShapes.completion(
			id = Ids.chatCompletion(),
			created = Instant.now().epochSecond,
			model = directive.model,
			content = FakeText.content(outputTokens),
			finishReason = finishReason,
			usage = OpenAiShapes.usage(directive.inputTokens, directive.cachedInputTokens, outputTokens),
		)
		writer.json(response, HttpStatus.OK, body)
		return HttpStatus.OK.value()
	}

	private suspend fun stream(response: ServerHttpResponse, request: OpenAiRequest, directive: Directive): Int {
		val (outputTokens, finishReason) = outcome(request, directive)
		val id = Ids.chatCompletion()
		val created = Instant.now().epochSecond
		fun chunk(delta: Map<String, Any?>, finish: String? = null): String =
			OpenAiShapes.frame(writer.toJson(OpenAiShapes.chunk(id, created, directive.model, delta, finish, request.includeUsage)))

		val head = listOf(chunk(linkedMapOf("role" to "assistant", "content" to "")))
		val tail = buildList {
			add(chunk(emptyMap(), finishReason))
			if (request.includeUsage) {
				val usage = OpenAiShapes.usage(directive.inputTokens, directive.cachedInputTokens, outputTokens)
				add(OpenAiShapes.frame(writer.toJson(OpenAiShapes.usageChunk(id, created, directive.model, usage))))
			}
			add(OpenAiShapes.DONE_FRAME)
		}
		val errorFrame = OpenAiShapes.frame(writer.toJson(FakeErrors.openAi(500, FakeErrors.openAiMessage(500))))
		val frames = streamFrames(
			directive = directive,
			outputTokens = outputTokens,
			head = head,
			contentFrame = { index -> chunk(linkedMapOf("content" to FakeText.chunk(index))) },
			tail = tail,
			errorFrame = errorFrame,
		)
		writer.stream(response, frames)
		return HttpStatus.OK.value()
	}

	/** `max_tokens` below the directive's output caps the text and ends with `length`, as the real API would. */
	private fun outcome(request: OpenAiRequest, directive: Directive): Pair<Int, String> {
		val max = request.maxTokens
		return if (max != null && max < directive.outputTokens) max to "length" else directive.outputTokens to "stop"
	}

	companion object {
		const val PATH = "/v1/chat/completions"
	}
}
