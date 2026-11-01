package fakeprovider.directive

/**
 * What one request asked the fake to do, parsed from the model field: `fake-small:in=120,cached=20,out=8,ttft=200`.
 * Token counts are what the response will report; delays are in milliseconds.
 */
data class Directive(
	/** The model name without directives; echoed back as the response `model`. */
	val model: String,
	/** Input tokens in total, including the cached part. */
	val inputTokens: Int,
	/** Input tokens served from the prompt cache; never more than [inputTokens]. */
	val cachedInputTokens: Int,
	/** Output tokens, which is also the number of generated words and stream chunks. */
	val outputTokens: Int,
	val ttftMs: Long,
	val gapMs: Long,
	/** Respond at once with this HTTP status and a protocol-shaped error body. */
	val errorStatus: Int? = null,
	/** Close the connection after this many content chunks, without any terminating frame. */
	val abortAfter: Int? = null,
	/** Send an error frame/event after this many content chunks and end the stream. */
	val streamErrorAfter: Int? = null,
)

class DirectiveException(message: String) : RuntimeException(message)
