package tokentollgate.pricing

import tokentollgate.db.ModelPrice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Token counts as the gateway records them: [inputTokens] is the whole prompt, including the part the
 * provider served from its prompt cache, and [cachedInputTokens] is that cached part.
 */
data class TokenUsage(
	val inputTokens: Int,
	val outputTokens: Int,
	val cachedInputTokens: Int = 0,
)

/**
 * cost = (input - cached) x input rate + cached x (cached rate, or the input rate when there is none)
 *      + output x output rate, with rates per million tokens, rounded half-up to 8 decimals
 * (the scale of `usage_records.cost`). Never throws: negative counts count as zero and a cached count
 * above the input count is capped, so a malformed usage still produces a record.
 */
object CostCalculator {
	const val SCALE = 8
	private val TOKENS_PER_RATE_UNIT = BigDecimal(1_000_000)

	fun cost(price: ModelPrice, usage: TokenUsage): BigDecimal {
		val input = usage.inputTokens.coerceAtLeast(0)
		val output = usage.outputTokens.coerceAtLeast(0)
		val cached = usage.cachedInputTokens.coerceIn(0, input)
		val uncached = input - cached
		val cachedRate = price.cachedInputPerMtok ?: price.inputPerMtok
		val total = BigDecimal(uncached) * price.inputPerMtok +
			BigDecimal(cached) * cachedRate +
			BigDecimal(output) * price.outputPerMtok
		return total.divide(TOKENS_PER_RATE_UNIT, SCALE, RoundingMode.HALF_UP)
	}
}
