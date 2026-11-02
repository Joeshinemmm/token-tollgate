package tokentollgate.relay.openai

import tokentollgate.pricing.TokenUsage
import tools.jackson.databind.JsonNode

/** OpenAI `usage` → [TokenUsage]. `prompt_tokens` already includes the cached part reported in `prompt_tokens_details`. */
object OpenAiUsageParser {

	fun parse(usage: JsonNode): TokenUsage? {
		if (!usage.isObject) return null
		val prompt = usage.path("prompt_tokens")
		val completion = usage.path("completion_tokens")
		if (!prompt.isNumber || !completion.isNumber) return null
		val cached = usage.path("prompt_tokens_details").path("cached_tokens").let { if (it.isNumber) it.intValue() else 0 }
		return TokenUsage(inputTokens = prompt.intValue(), outputTokens = completion.intValue(), cachedInputTokens = cached)
	}
}
