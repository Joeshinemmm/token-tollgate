package tokentollgate.pricing

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import tokentollgate.IntegrationTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class PriceImportTest {

	@Autowired lateinit var pricing: PricingService

	@Test
	fun `import inserts missing rows and skips rows already present`(@TempDir dir: Path) = runBlocking<Unit> {
		val provider = "file-${UUID.randomUUID()}"
		val file = dir.resolve("prices.yaml")
		Files.writeString(
			file,
			"""
			prices:
			  - provider: $provider
			    model: alpha
			    input_per_mtok: 1.00
			    cached_input_per_mtok: 0.10
			    output_per_mtok: 2.00
			    effective_from: 2026-01-01
			  - provider: $provider
			    model: beta
			    input_per_mtok: 0.50
			    output_per_mtok: 1.50
			    effective_from: 2026-01-01T12:00:00Z
			""".trimIndent(),
		)

		assertEquals(ImportResult(inserted = 2, skipped = 0), pricing.importFile(file))
		assertEquals(ImportResult(inserted = 0, skipped = 2), pricing.importFile(file), "a second import changes nothing")

		val rows = pricing.list(provider)
		assertEquals(listOf("alpha", "beta"), rows.map { it.model })
		assertEquals(BigDecimal("0.10000000"), rows[0].cachedInputPerMtok)
		assertEquals(Instant.parse("2026-01-01T00:00:00Z"), rows[0].effectiveFrom)
		assertNull(rows[1].cachedInputPerMtok)
		assertEquals(Instant.parse("2026-01-01T12:00:00Z"), rows[1].effectiveFrom)
	}

	@Test
	fun `a file with an invalid entry inserts nothing`(@TempDir dir: Path) = runBlocking<Unit> {
		val provider = "file-${UUID.randomUUID()}"
		val file = dir.resolve("prices.yaml")
		Files.writeString(
			file,
			"""
			prices:
			  - provider: $provider
			    model: alpha
			    input_per_mtok: 1.00
			    output_per_mtok: 2.00
			    effective_from: 2026-01-01
			  - provider: $provider
			    model: beta
			    input_per_mtok: 0.50
			    output_per_mtok: 1.50
			    effective_from: not-a-date
			""".trimIndent(),
		)
		assertFailsWith<PriceFileException> { pricing.importFile(file) }
		assertTrue(pricing.list(provider).isEmpty())
	}

	@Test
	fun `a missing file is a clear error`() = runBlocking<Unit> {
		val error = assertFailsWith<PriceFileException> { pricing.importFile(Path.of("does-not-exist.yaml")) }
		assertTrue(error.message.orEmpty().startsWith("Cannot read price file"), error.message)
	}
}
