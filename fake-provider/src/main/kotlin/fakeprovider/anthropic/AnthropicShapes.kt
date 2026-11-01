package fakeprovider.anthropic

/**
 * Response objects and stream events in the shape of Anthropic's Messages reference and streaming guide
 * (dev-log 005 lists the pages checked). Ordered maps keep key order as in the documented examples.
 */
object AnthropicShapes {

	fun event(type: String, json: String): String = "event: $type\ndata: $json\n\n"

	/** Anthropic counts cache reads outside `input_tokens`: total input = input + cache creation + cache read. */
	fun usage(inputTokens: Int, cachedInputTokens: Int, outputTokens: Int): Map<String, Any?> = linkedMapOf(
		"input_tokens" to inputTokens - cachedInputTokens,
		"cache_creation_input_tokens" to 0,
		"cache_read_input_tokens" to cachedInputTokens,
		"output_tokens" to outputTokens,
	)

	fun message(id: String, model: String, text: String, stopReason: String, usage: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
		"id" to id,
		"type" to "message",
		"role" to "assistant",
		"model" to model,
		"content" to listOf(linkedMapOf<String, Any?>("type" to "text", "text" to text)),
		"stop_reason" to stopReason,
		"stop_sequence" to null,
		"usage" to usage,
	)

	fun messageStart(id: String, model: String, usage: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
		"type" to "message_start",
		"message" to linkedMapOf<String, Any?>(
			"id" to id,
			"type" to "message",
			"role" to "assistant",
			"model" to model,
			"content" to emptyList<Any>(),
			"stop_reason" to null,
			"stop_sequence" to null,
			"usage" to usage,
		),
	)

	fun contentBlockStart(): Map<String, Any?> = linkedMapOf(
		"type" to "content_block_start",
		"index" to 0,
		"content_block" to linkedMapOf<String, Any?>("type" to "text", "text" to ""),
	)

	fun ping(): Map<String, Any?> = linkedMapOf("type" to "ping")

	fun contentBlockDelta(text: String): Map<String, Any?> = linkedMapOf(
		"type" to "content_block_delta",
		"index" to 0,
		"delta" to linkedMapOf<String, Any?>("type" to "text_delta", "text" to text),
	)

	fun contentBlockStop(): Map<String, Any?> = linkedMapOf("type" to "content_block_stop", "index" to 0)

	/** `usage.output_tokens` is cumulative, so for a single block it is the final total. */
	fun messageDelta(stopReason: String, outputTokens: Int): Map<String, Any?> = linkedMapOf(
		"type" to "message_delta",
		"delta" to linkedMapOf<String, Any?>("stop_reason" to stopReason, "stop_sequence" to null),
		"usage" to linkedMapOf<String, Any?>("output_tokens" to outputTokens),
	)

	fun messageStop(): Map<String, Any?> = linkedMapOf("type" to "message_stop")

	/** The mid-stream error example from the streaming guide. */
	fun overloadedError(): Map<String, Any?> = linkedMapOf(
		"type" to "error",
		"error" to linkedMapOf<String, Any?>("type" to "overloaded_error", "message" to "Overloaded"),
	)
}
