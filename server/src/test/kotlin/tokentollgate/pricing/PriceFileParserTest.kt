package tokentollgate.pricing

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceFileParserTest {

	@Test
	fun `parses snake_case entries with optional cached rate and both effective_from forms`() {
		val specs = PriceFileParser.parse(
			"""
			prices:
			  - provider: openai
			    model: gpt-x
			    input_per_mtok: 2.00
			    cached_input_per_mtok: 0.10
			    output_per_mtok: 10.00
			    effective_from: 2026-11-01
			  - provider: fake
			    model: fake-small
			    input_per_mtok: 1
			    output_per_mtok: 2
			    currency: USD
			    effective_from: 2026-01-01T12:00:00Z
			""".trimIndent(),
		)
		assertEquals(2, specs.size)
		assertEquals("gpt-x", specs[0].model)
		assertEquals(BigDecimal("0.10000000"), specs[0].cachedInputPerMtok)
		assertEquals(Instant.parse("2026-11-01T00:00:00Z"), specs[0].effectiveFrom)
		assertNull(specs[1].cachedInputPerMtok)
		assertEquals(BigDecimal("1.00000000"), specs[1].inputPerMtok)
		assertEquals(Instant.parse("2026-01-01T12:00:00Z"), specs[1].effectiveFrom)
	}

	@Test
	fun `an unknown key is an error, not silently ignored`() {
		val error = assertFailsWith<PriceFileException> {
			PriceFileParser.parse(
				"""
				prices:
				  - provider: openai
				    model: gpt-x
				    input_per_mtok: 2.00
				    output_per_mtk: 10.00
				    effective_from: 2026-11-01
				""".trimIndent(),
			)
		}
		assertTrue(error.message.orEmpty().contains("output_per_mtk"), error.message)
	}

	@Test
	fun `an invalid entry is reported with its index`() {
		val error = assertFailsWith<PriceFileException> {
			PriceFileParser.parse(
				"""
				prices:
				  - provider: openai
				    model: gpt-x
				    input_per_mtok: 2.00
				    output_per_mtok: 10.00
				    effective_from: 2026-11-01
				  - provider: openai
				    model: gpt-y
				    input_per_mtok: -1
				    output_per_mtok: 10.00
				    effective_from: 2026-11-01
				""".trimIndent(),
			)
		}
		assertTrue(error.message.orEmpty().startsWith("prices[1]: inputPerMtok"), error.message)
	}

	@Test
	fun `a file without a prices list is an error`() {
		assertFailsWith<PriceFileException> { PriceFileParser.parse("rates: []") }
		assertFailsWith<PriceFileException> { PriceFileParser.parse("") }
		assertEquals(emptyList(), PriceFileParser.parse("prices: []"))
	}

	@Test
	fun `the shipped example file parses`() {
		// Gradle runs tests with the module directory as the working directory.
		val specs = PriceFileParser.parse(Path.of("..", "config", "prices.example.yaml"))
		assertTrue(specs.isNotEmpty())
		assertEquals(setOf("openai", "anthropic", "fake", "ollama"), specs.map { it.provider }.toSet())
		val keys = specs.map { Triple(it.provider, it.model, it.effectiveFrom) }
		assertEquals(keys.size, keys.toSet().size, "no duplicate (provider, model, effective_from)")
	}
}
