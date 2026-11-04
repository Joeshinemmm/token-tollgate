package tokentollgate.relay.openai

import tokentollgate.pricing.TokenUsage
import tokentollgate.relay.SseFrame
import tokentollgate.relay.StreamObserver
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Watches the frames of one streamed Chat Completions response on their way to the client: remembers the model,
 * the moment of the first content, the usage chunk and any error frame, and decides whether the usage chunk is
 * forwarded. Everything else is forwarded untouched, including frames it cannot parse.
 */
class OpenAiStreamObserver(private val mapper: ObjectMapper, private val forwardUsageChunk: Boolean) : StreamObserver {

	override var model: String? = null
		private set
	override var usage: TokenUsage? = null
		private set
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
		if (data.trim() == DONE) {
			done = true
			return true
		}
		val node = try {
			mapper.readTree(data)
		} catch (e: JacksonException) {
			return true
		}
		if (node == null || !node.isObject) return true

		val error = node.path("error")
		if (error.isObject) {
			sawError = true
			errorType = error.path("type").let { if (it.isString) it.stringValue() else null }
			return true
		}
		if (model == null) node.path("model").let { if (it.isString) model = it.stringValue() }

		val choices = node.path("choices")
		val usageNode = node.path("usage")
		if (choices.isArray && choices.size() == 0 && usageNode.isObject) {
			usage = OpenAiUsageParser.parse(usageNode)
			return forwardUsageChunk
		}
		if (firstContentNanos == null && hasContent(choices)) firstContentNanos = System.nanoTime()
		return true
	}

	private fun hasContent(choices: JsonNode): Boolean {
		if (!choices.isArray) return false
		for (i in 0 until choices.size()) {
			val delta = choices.path(i).path("delta")
			val content = delta.path("content")
			if (content.isString && content.stringValue().isNotEmpty()) return true
			if (delta.path("tool_calls").isArray) return true
		}
		return false
	}

	companion object {
		const val DONE = "[DONE]"
	}
}
