package tokentollgate.pricing

import org.junit.jupiter.api.Test
import tokentollgate.db.ModelPrice
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class CostCalculatorTest {

	private fun price(input: String, output: String, cached: String? = null) = ModelPrice(
		provider = "p",
		model = "m",
		inputPerMtok = BigDecimal(input),
		outputPerMtok = BigDecimal(output),
		cachedInputPerMtok = cached?.let(::BigDecimal),
		effectiveFrom = Instant.EPOCH,
	)

	@Test
	fun `input and output tokens are charged at their rates per million`() {
		// 1,000 x 2.00 + 500 x 10.00 = 2,000 + 5,000 = 7,000 per million
		assertEquals(BigDecimal("0.00700000"), CostCalculator.cost(price("2.00", "10.00"), TokenUsage(1_000, 500)))
	}

	@Test
	fun `cached input tokens use the cached rate and the rest the input rate`() {
		// 2,000 x 2.00 + 8,000 x 0.10 + 1,000 x 10.00 = 4,000 + 800 + 10,000 = 14,800 per million
		val usage = TokenUsage(inputTokens = 10_000, outputTokens = 1_000, cachedInputTokens = 8_000)
		assertEquals(BigDecimal("0.01480000"), CostCalculator.cost(price("2.00", "10.00", "0.10"), usage))
	}

	@Test
	fun `without a cached rate, cached tokens cost the full input rate`() {
		val usage = TokenUsage(inputTokens = 10_000, outputTokens = 1_000, cachedInputTokens = 8_000)
		assertEquals(BigDecimal("0.03000000"), CostCalculator.cost(price("2.00", "10.00"), usage))
	}

	@Test
	fun `zero tokens cost zero at scale 8`() {
		assertEquals(BigDecimal("0.00000000"), CostCalculator.cost(price("2.00", "10.00"), TokenUsage(0, 0)))
	}

	@Test
	fun `the result is rounded half-up to 8 decimals`() {
		assertEquals(BigDecimal("0.00000013"), CostCalculator.cost(price("0.125", "0"), TokenUsage(1, 0)))
		assertEquals(BigDecimal("0.00000012"), CostCalculator.cost(price("0.123", "0"), TokenUsage(1, 0)))
	}

	@Test
	fun `cached tokens above the input count are capped and negative counts count as zero`() {
		val capped = TokenUsage(inputTokens = 100, outputTokens = 0, cachedInputTokens = 150)
		assertEquals(BigDecimal("0.00001000"), CostCalculator.cost(price("2.00", "10.00", "0.10"), capped))
		assertEquals(BigDecimal("0.00000000"), CostCalculator.cost(price("2.00", "10.00"), TokenUsage(-5, -1, -3)))
	}
}
