package tokentollgate.relay

import io.netty.handler.timeout.ReadTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tokentollgate.db.Outcome
import tokentollgate.db.UsageSource
import tokentollgate.db.dbNow
import tokentollgate.gateway.ProtocolErrorWriter
import tokentollgate.pricing.TokenUsage
import tokentollgate.security.AuthenticatedKey
import tokentollgate.upstream.Provider
import tokentollgate.upstream.ProviderRegistry
import tokentollgate.upstream.Route
import tokentollgate.usage.UsageDraft
import tokentollgate.usage.UsageRecorder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relays one request to the provider that owns its model, in whatever protocol the [ProtocolAdapter] speaks.
 * Plain responses are buffered and returned unchanged; streams are forwarded frame by frame while the adapter's
 * observer reads usage on the side. Whatever happens, one usage record is written through [UsageRecorder].
 */
@Component
class RelayEngine(
	private val registry: ProviderRegistry,
	private val recorder: UsageRecorder,
	private val errorWriter: ProtocolErrorWriter,
) {

	private val log = LoggerFactory.getLogger(RelayEngine::class.java)

	suspend fun relay(adapter: ProtocolAdapter, exchange: ServerWebExchange, key: AuthenticatedKey, body: ByteArray?) {
		val requestId = UUID.randomUUID()
		exchange.response.headers.set(HeaderRules.REQUEST_ID_HEADER, requestId.toString())

		val inspected = try {
			adapter.inspect(body ?: ByteArray(0))
		} catch (e: InvalidRelayRequestException) {
			writeError(adapter, exchange, requestId, RelayErrors.badRequest(e.message ?: "Invalid request", e.param))
			return
		}
		val attempt = Attempt(adapter, requestId, key, inspected)
		val provider = when (val route = registry.route(adapter.providerType, inspected.model)) {
			is Route.To -> route.provider
			is Route.Disabled -> {
				attempt.refuse(exchange, RelayErrors.providerDisabled(route.provider), provider = route.provider.name)
				return
			}
			Route.NotFound -> {
				attempt.refuse(exchange, RelayErrors.modelNotFound(inspected.model))
				return
			}
		}
		attempt.provider = provider.name
		try {
			if (inspected.stream) streamUpstream(exchange, provider, attempt) else callUpstream(exchange, provider, attempt)
			attempt.finish()
		} catch (e: CancellationException) {
			attempt.abortedByClient(exchange)
			throw e
		} catch (e: Exception) {
			attempt.failed(exchange, provider, e)
		}
	}

	private suspend fun callUpstream(exchange: ServerWebExchange, provider: Provider, attempt: Attempt) {
		upstreamRequest(exchange, provider, attempt).awaitExchange { upstream ->
			val status = upstream.statusCode()
			val headers = upstream.headers().asHttpHeaders()
			relayPlain(exchange, upstream, status, headers, attempt)
		}
	}

	private suspend fun streamUpstream(exchange: ServerWebExchange, provider: Provider, attempt: Attempt) {
		upstreamRequest(exchange, provider, attempt).awaitExchange { upstream ->
			val status = upstream.statusCode()
			val headers = upstream.headers().asHttpHeaders()
			val contentType = headers.contentType
			if (!status.is2xxSuccessful || contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
				relayPlain(exchange, upstream, status, headers, attempt)
				return@awaitExchange
			}

			val response = exchange.response
			response.statusCode = status
			HeaderRules.copyResponseHeaders(headers, response.headers)
			val factory = response.bufferFactory()
			val splitter = SseFrameSplitter()
			val observer = attempt.adapter.streamObserver(attempt.inspected)
			val frames: Flux<Publisher<DataBuffer>> = upstream.bodyToFlux(DataBuffer::class.java)
				.timeout(provider.settings.streamIdleTimeout)
				.concatMap { buffer -> Flux.fromIterable(splitter.feed(drain(buffer))) }
				.concatWith(Flux.defer { Mono.justOrEmpty(splitter.finish()) })
				.filter { frame -> observer.observe(frame) }
				.map { frame -> frame.raw }
				.onErrorResume { e ->
					val failure = streamFailure(provider, e)
					attempt.streamFailure = failure
					Flux.just(attempt.adapter.errorFrame(failure.error, attempt.requestId))
				}
				.doOnCancel { attempt.cancelled = true }
				.map { bytes -> Mono.just(factory.wrap(bytes)) }
			response.writeAndFlushWith(frames).awaitSingleOrNull()
			attempt.streamResponse(status, observer)
		}
	}

	private fun upstreamRequest(exchange: ServerWebExchange, provider: Provider, attempt: Attempt) =
		registry.client(provider).post()
			.uri(exchange.request.path.value())
			.headers { headers ->
				HeaderRules.copyRequestHeaders(exchange.request.headers, headers)
				provider.apiKey?.let { headers.setBearerAuth(it) }
				attempt.adapter.prepareUpstreamHeaders(headers)
			}
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(attempt.inspected.upstreamBody)

	private suspend fun relayPlain(exchange: ServerWebExchange, upstream: ClientResponse, status: HttpStatusCode, headers: HttpHeaders, attempt: Attempt) {
		val bytes = upstream.bodyToMono(ByteArray::class.java).awaitSingleOrNull() ?: ByteArray(0)
		writePlain(exchange.response, status, headers, bytes)
		attempt.plainResponse(status, bytes)
	}

	private suspend fun writePlain(response: ServerHttpResponse, status: HttpStatusCode, headers: HttpHeaders, bytes: ByteArray) {
		response.statusCode = status
		HeaderRules.copyResponseHeaders(headers, response.headers)
		response.writeWith(Mono.just(response.bufferFactory().wrap(bytes))).awaitSingleOrNull()
	}

	private suspend fun writeError(adapter: ProtocolAdapter, exchange: ServerWebExchange, requestId: UUID, error: GatewayError) {
		errorWriter.writeJson(exchange, error.status, adapter.errorBody(error, requestId)).awaitSingleOrNull()
	}

	private fun drain(buffer: DataBuffer): ByteArray {
		val bytes = ByteArray(buffer.readableByteCount())
		buffer.read(bytes)
		DataBufferUtils.release(buffer)
		return bytes
	}

	private fun isTimeout(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is ReadTimeoutException || it is TimeoutException }

	private data class StreamFailure(val error: GatewayError, val outcome: Outcome, val errorType: String)

	private fun streamFailure(provider: Provider, e: Throwable): StreamFailure =
		if (isTimeout(e)) {
			StreamFailure(RelayErrors.streamTimeout(provider), Outcome.TIMEOUT, "upstream_timeout")
		} else {
			log.debug("upstream stream from {} ended early: {}", provider.name, e.toString())
			StreamFailure(RelayErrors.streamInterrupted(provider), Outcome.UPSTREAM_ERROR, "stream_interrupted")
		}

	/** Mutable state of one relayed request; [finish] records it exactly once. */
	private inner class Attempt(val adapter: ProtocolAdapter, val requestId: UUID, val key: AuthenticatedKey, val inspected: InspectedRequest) {
		val startedAt: Instant = dbNow()
		private val startNanos = System.nanoTime()
		private val recorded = AtomicBoolean(false)

		var provider: String = "none"
		var modelServed: String? = null
		var usage: TokenUsage? = null
		var usageSource: UsageSource = UsageSource.NONE
		var ttftMs: Int? = null
		var httpStatus: Int = 0
		var outcome: Outcome = Outcome.GATEWAY_ERROR
		var errorType: String? = null
		var streamFailure: StreamFailure? = null
		@Volatile var cancelled: Boolean = false

		fun plainResponse(status: HttpStatusCode, bytes: ByteArray) {
			httpStatus = status.value()
			val parsed = adapter.parsePlain(status, bytes)
			modelServed = parsed.modelServed
			if (status.is2xxSuccessful) {
				outcome = Outcome.OK
				usage = parsed.usage
				if (usage != null) usageSource = UsageSource.RESPONSE
			} else {
				outcome = Outcome.UPSTREAM_ERROR
				errorType = parsed.errorType ?: "http_${status.value()}"
			}
		}

		fun streamResponse(status: HttpStatusCode, observer: StreamObserver) {
			httpStatus = status.value()
			modelServed = observer.model
			ttftMs = observer.firstContentNanos?.let { ((it - startNanos) / 1_000_000).toInt() }
			val failure = streamFailure
			when {
				cancelled -> outcome = Outcome.CLIENT_ABORT
				failure != null -> {
					outcome = failure.outcome
					errorType = failure.errorType
				}
				observer.sawError -> {
					outcome = Outcome.UPSTREAM_ERROR
					errorType = observer.errorType ?: "stream_error"
				}
				observer.done -> {
					outcome = Outcome.OK
					usage = observer.usage
					if (usage != null) usageSource = UsageSource.STREAM
				}
				else -> {
					outcome = Outcome.UPSTREAM_ERROR
					errorType = "stream_interrupted"
				}
			}
		}

		/** A request refused before anything went upstream: routing failures. */
		suspend fun refuse(exchange: ServerWebExchange, error: GatewayError, provider: String = "none") {
			this.provider = provider
			httpStatus = error.status.value()
			outcome = Outcome.GATEWAY_ERROR
			errorType = error.code
			writeError(adapter, exchange, requestId, error)
			finish()
		}

		/** The upstream call itself threw: timeout, connection failure or anything unexpected. */
		suspend fun failed(exchange: ServerWebExchange, upstream: Provider, e: Exception) {
			val error = when {
				isTimeout(e) -> RelayErrors.upstreamTimeout(upstream)
				e is WebClientRequestException -> RelayErrors.upstreamUnreachable(upstream)
				else -> RelayErrors.gatewayFailure()
			}
			if (error.code == "gateway_error") log.error("relay failed for request {}", requestId, e) else log.warn("relay failed for request {}: {}", requestId, e.toString())
			if (exchange.response.isCommitted) {
				// The client already has a status line; a client that went away shows up here as a write failure.
				if (cancelled || httpStatus == 0) outcome = Outcome.CLIENT_ABORT
				if (httpStatus == 0) httpStatus = exchange.response.statusCode?.value() ?: 200
				finish()
				return
			}
			httpStatus = error.status.value()
			outcome = when (error.code) {
				"upstream_timeout" -> Outcome.TIMEOUT
				"gateway_error" -> Outcome.GATEWAY_ERROR
				else -> Outcome.UPSTREAM_ERROR
			}
			errorType = error.code
			writeError(adapter, exchange, requestId, error)
			finish()
		}

		fun abortedByClient(exchange: ServerWebExchange) {
			outcome = Outcome.CLIENT_ABORT
			if (httpStatus == 0) httpStatus = exchange.response.statusCode?.value() ?: 0
			finish()
		}

		fun finish() {
			if (!recorded.compareAndSet(false, true)) return
			recorder.record(
				UsageDraft(
					requestId = requestId,
					startedAt = startedAt,
					key = key,
					protocol = adapter.protocol,
					provider = provider,
					modelRequested = inspected.model,
					modelServed = modelServed,
					usage = usage,
					usageSource = usageSource,
					ttftMs = ttftMs,
					durationMs = ((System.nanoTime() - startNanos) / 1_000_000).toInt(),
					httpStatus = httpStatus,
					streamed = inspected.stream,
					outcome = outcome,
					errorType = errorType,
				),
			)
		}
	}
}
