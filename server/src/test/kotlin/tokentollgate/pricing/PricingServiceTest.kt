package tokentollgate.pricing

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tokentollgate.IntegrationTest
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class PricingServiceTest {

	@Autowired lateinit var pricing: PricingService

	private fun spec(provider: String, input: String, effectiveFrom: Instant) = PriceSpec(
		provider = provider,
		model = "m",
		inputPerMtok = BigDecimal(input).setScale(8),
		outputPerMtok = BigDecimal("10.00000000"),
		cachedInputPerMtok = null,
		currency = "USD",
		effectiveFrom = effectiveFrom,
	)

	@Test
	fun `the latest price not after the request time applies, none before the first row or for unknown models`() = runBlocking<Unit> {
		val provider = "p-${UUID.randomUUID()}"
		val t = Instant.parse("2026-06-15T00:00:00Z")
		val old = pricing.upsert(spec(provider, "1.00", t.minus(2, DAYS))).price
		val current = pricing.upsert(spec(provider, "2.00", t.minus(1, DAYS))).price
		pricing.upsert(spec(provider, "3.00", t.plus(1, DAYS)))

		assertEquals(current.id, pricing.priceFor(provider, "m", t)?.id)
		assertEquals(current.id, pricing.priceFor(provider, "m", t.minus(1, DAYS))?.id, "a price applies from its effectiveFrom inclusive")
		assertEquals(old.id, pricing.priceFor(provider, "m", t.minus(1, DAYS).minusSeconds(1))?.id)
		assertNull(pricing.priceFor(provider, "m", t.minus(3, DAYS)), "nothing is in force before the first row")
		assertNull(pricing.priceFor(provider, "other", t))

		assertEquals(
			CostResult.Priced(BigDecimal("2.00000000"), requireNotNull(current.id)),
			pricing.cost(provider, "m", t, TokenUsage(inputTokens = 1_000_000, outputTokens = 0)),
		)
		assertEquals(CostResult.Unpriced, pricing.cost(provider, "other", t, TokenUsage(1, 1)))
	}

	@Test
	fun `upsert replaces the rates of an existing row instead of adding one`() = runBlocking<Unit> {
		val provider = "p-${UUID.randomUUID()}"
		val at = Instant.parse("2026-06-15T00:00:00Z")
		val first = pricing.upsert(spec(provider, "1.00", at))
		val second = pricing.upsert(spec(provider, "5.00", at))
		assertTrue(first.created)
		assertFalse(second.created)
		assertEquals(first.price.id, second.price.id)
		val rows = pricing.list(provider)
		assertEquals(1, rows.size)
		assertEquals(BigDecimal("5.00000000"), rows.single().inputPerMtok)
	}
}
