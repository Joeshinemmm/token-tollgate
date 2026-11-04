package tokentollgate.relay.anthropic

import tokentollgate.pricing.TokenUsage
import tokentollgate.relay.SseFrame
import tokentollgate.relay.StreamObserver
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Watches a Messages stream: model and baseline usage from `message_start`, the first `content_block_delta` as the
 * first token, cumulative usage from `message_delta`, `message_stop` as the normal end, `error` events as failures.
 * Every frame is forwarded; `ping` and unknown events pass through untouched, as the API's versioning policy asks.
 */
class AnthropicStreamObserver(private val mapper: ObjectMapper) : StreamObserver {

	private val accumulator = AnthropicUsageParser.Accumulator()

	override var model: String? = null
		private set
	override val usage: TokenUsage? get() = accumulator.toUsage()
	override var done: Boolean = false
		private set
	override var sawError: Boolean = false
		private set
	override var errorType: String? = null
		private set
	override var firstContentNanos: Long? = null
		private set

	override fun observe(frame: SseFrame): Boolean {
		val data = frame.data ?: return true
		val node = try {
			mapper.readTree(data)
		} catch (e: JacksonException) {
			return true
		}
		if (node == null || !node.isObject) return true
		when (node.path("type").let { if (it.isString) it.stringValue() else "" }) {
			"message_start" -> {
				val message = node.path("message")
				if (model == null) message.path("model").let { if (it.isString) model = it.stringValue() }
				accumulator.start(message.path("usage"))
			}
			"content_block_delta" -> if (firstContentNanos == null) firstContentNanos = System.nanoTime()
			"message_delta" -> accumulator.delta(node.path("usage"))
			"message_stop" -> done = true
			"error" -> {
				sawError = true
				errorType = node.path("error").path("type").let { if (it.isString) it.stringValue() else null }
			}
		}
		return true
	}
}
