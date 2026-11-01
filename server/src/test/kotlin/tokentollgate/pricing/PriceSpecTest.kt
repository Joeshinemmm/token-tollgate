package tokentollgate.pricing

import org.junit.jupiter.api.Test
import tokentollgate.common.BadRequestException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceSpecTest {

	private fun spec(
		provider: String? = "openai",
		model: String? = "gpt-x",
		input: String? = "2.00",
		output: String? = "10.00",
		cached: String? = null,
		currency: String? = null,
		effectiveFrom: String? = "2026-11-01",
	) = PriceSpec.of(
		provider = provider,
		model = model,
		inputPerMtok = input?.let(::BigDecimal),
		outputPerMtok = output?.let(::BigDecimal),
		cachedInputPerMtok = cached?.let(::BigDecimal),
		currency = currency,
		effectiveFrom = effectiveFrom,
	)

	@Test
	fun `names are trimmed, currency defaults to USD, rates get scale 8 and a date means midnight UTC`() {
		val spec = spec(provider = "  openai ", model = " gpt-x", input = "2", cached = "0.1")
		assertEquals("openai", spec.provider)
		assertEquals("gpt-x", spec.model)
		assertEquals("USD", spec.currency)
		assertEquals(BigDecimal("2.00000000"), spec.inputPerMtok)
		assertEquals(BigDecimal("0.10000000"), spec.cachedInputPerMtok)
		assertEquals(Instant.parse("2026-11-01T00:00:00Z"), spec.effectiveFrom)
		assertNull(spec(cached = null).cachedInputPerMtok)
	}

	@Test
	fun `an ISO-8601 instant is accepted as effectiveFrom`() {
		assertEquals(Instant.parse("2026-11-01T09:30:00Z"), spec(effectiveFrom = "2026-11-01T09:30:00Z").effectiveFrom)
	}

	@Test
	fun `invalid input is rejected with a message naming the field`() {
		fun rejects(expected: String, block: () -> PriceSpec) {
			val message = assertFailsWith<BadRequestException> { block() }.message.orEmpty()
			assertTrue(message.contains(expected), "expected [$expected] in [$message]")
		}
		rejects("provider") { spec(provider = "  ") }
		rejects("model") { spec(model = null) }
		rejects("inputPerMtok is required") { spec(input = null) }
		rejects("outputPerMtok must not be negative") { spec(output = "-1") }
		rejects("cachedInputPerMtok must have at most 8 decimal places") { spec(cached = "0.000000001") }
		rejects("inputPerMtok is too large") { spec(input = "10000000000") }
		rejects("currency must be USD") { spec(currency = "EUR") }
		rejects("effectiveFrom is required") { spec(effectiveFrom = null) }
		rejects("effectiveFrom must be a date") { spec(effectiveFrom = "2026/11/01") }
		rejects("effectiveFrom must be a date") { spec(effectiveFrom = "yesterday") }
	}
}
