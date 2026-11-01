package fakeprovider.openai

/**
 * Response objects in the shape of OpenAI's Chat Completions reference (dev-log 005 lists the pages checked).
 * Built as ordered maps so that key presence and order match the documented examples exactly.
 */
object OpenAiShapes {

	const val SYSTEM_FINGERPRINT = "fp_fake"
	const val DONE_FRAME = "data: [DONE]\n\n"

	fun frame(json: String): String = "data: $json\n\n"

	/** `prompt_tokens` includes the cached part; `prompt_tokens_details.cached_tokens` is that part. */
	fun usage(inputTokens: Int, cachedInputTokens: Int, outputTokens: Int): Map<String, Any?> = linkedMapOf(
		"prompt_tokens" to inputTokens,
		"completion_tokens" to outputTokens,
		"total_tokens" to inputTokens + outputTokens,
		"prompt_tokens_details" to linkedMapOf("cached_tokens" to cachedInputTokens, "audio_tokens" to 0),
		"completion_tokens_details" to linkedMapOf(
			"reasoning_tokens" to 0,
			"audio_tokens" to 0,
			"accepted_prediction_tokens" to 0,
			"rejected_prediction_tokens" to 0,
		),
	)

	fun completion(id: String, created: Long, model: String, content: String, finishReason: String, usage: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
		"id" to id,
		"object" to "chat.completion",
		"created" to created,
		"model" to model,
		"choices" to listOf(
			linkedMapOf<String, Any?>(
				"index" to 0,
				"message" to linkedMapOf<String, Any?>("role" to "assistant", "content" to content, "refusal" to null, "annotations" to emptyList<Any>()),
				"logprobs" to null,
				"finish_reason" to finishReason,
			),
		),
		"usage" to usage,
		"service_tier" to "default",
		"system_fingerprint" to SYSTEM_FINGERPRINT,
	)

	/** With `include_usage`, every chunk but the usage chunk carries `"usage": null`; without it the key is absent. */
	fun chunk(id: String, created: Long, model: String, delta: Map<String, Any?>, finishReason: String?, includeUsage: Boolean): Map<String, Any?> {
		val chunk = linkedMapOf<String, Any?>(
			"id" to id,
			"object" to "chat.completion.chunk",
			"created" to created,
			"model" to model,
			"system_fingerprint" to SYSTEM_FINGERPRINT,
			"choices" to listOf(linkedMapOf<String, Any?>("index" to 0, "delta" to delta, "logprobs" to null, "finish_reason" to finishReason)),
		)
		if (includeUsage) chunk["usage"] = null
		return chunk
	}

	fun usageChunk(id: String, created: Long, model: String, usage: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
		"id" to id,
		"object" to "chat.completion.chunk",
		"created" to created,
		"model" to model,
		"system_fingerprint" to SYSTEM_FINGERPRINT,
		"choices" to emptyList<Any>(),
		"usage" to usage,
	)
}
