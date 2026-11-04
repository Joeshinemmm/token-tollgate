package tokentollgate.relay.anthropic

import tokentollgate.pricing.TokenUsage
import tools.jackson.databind.JsonNode

/**
 * Anthropic `usage` → [TokenUsage]. Anthropic splits the input into three numbers and documents that the total
 * input is their sum; the gateway records that sum as `input_tokens` and the cache reads as `cached_input_tokens`.
 * Cache-creation tokens are priced at the plain input rate for now (the price table has no cache-write rate).
 */
object AnthropicUsageParser {

	fun parse(usage: JsonNode): TokenUsage? = Accumulator().apply { start(usage) }.toUsage()

	/** Stream usage: `message_start` sets the baseline, each `message_delta` overrides the fields it carries (they are cumulative). */
	class Accumulator {
		var inputTokens: Int? = null
			private set
		var cacheCreationTokens: Int? = null
			private set
		var cacheReadTokens: Int? = null
			private set
		var outputTokens: Int? = null
			private set

		fun start(usage: JsonNode) {
			if (!usage.isObject) return
			inputTokens = int(usage, "input_tokens")
			cacheCreationTokens = int(usage, "cache_creation_input_tokens")
			cacheReadTokens = int(usage, "cache_read_input_tokens")
			outputTokens = int(usage, "output_tokens")
		}

		fun delta(usage: JsonNode) {
			if (!usage.isObject) return
			int(usage, "input_tokens")?.let { inputTokens = it }
			int(usage, "cache_creation_input_tokens")?.let { cacheCreationTokens = it }
			int(usage, "cache_read_input_tokens")?.let { cacheReadTokens = it }
			int(usage, "output_tokens")?.let { outputTokens = it }
		}

		/** Null until both the input and the output are known. */
		fun toUsage(): TokenUsage? {
			val input = inputTokens ?: return null
			val output = outputTokens ?: return null
			val cacheRead = cacheReadTokens ?: 0
			return TokenUsage(inputTokens = input + (cacheCreationTokens ?: 0) + cacheRead, outputTokens = output, cachedInputTokens = cacheRead)
		}

		private fun int(node: JsonNode, name: String): Int? = node.path(name).let { if (it.isNumber) it.intValue() else null }
	}
}
