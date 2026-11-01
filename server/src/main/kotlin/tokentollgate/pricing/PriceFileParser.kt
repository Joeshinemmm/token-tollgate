package tokentollgate.pricing

import tokentollgate.common.BadRequestException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** Raw shape of one entry in the price file; validation happens in [PriceSpec.of]. */
data class PriceFileEntry(
	val provider: String? = null,
	val model: String? = null,
	val inputPerMtok: BigDecimal? = null,
	val outputPerMtok: BigDecimal? = null,
	val cachedInputPerMtok: BigDecimal? = null,
	val currency: String? = null,
	val effectiveFrom: String? = null,
)

data class PriceFile(val prices: List<PriceFileEntry>? = null)

class PriceFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads files shaped like `config/prices.example.yaml`: a `prices` list whose entries use snake_case
 * keys (`input_per_mtok`). Unknown keys are errors so that a typo cannot silently drop a rate.
 */
object PriceFileParser {
	private val mapper = YAMLMapper.builder()
		.addModule(kotlinModule())
		.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build()

	fun parse(path: Path): List<PriceSpec> {
		val text = try {
			Files.readString(path)
		} catch (e: IOException) {
			throw PriceFileException("Cannot read price file $path: ${e.message}", e)
		}
		return parse(text)
	}

	fun parse(text: String): List<PriceSpec> {
		val file: PriceFile? = try {
			mapper.readValue(text, PriceFile::class.java)
		} catch (e: JacksonException) {
			throw PriceFileException("Price file is not valid: ${e.originalMessage}", e)
		}
		val entries = file?.prices ?: throw PriceFileException("Price file must have a 'prices' list")
		return entries.mapIndexed { index, entry ->
			try {
				PriceSpec.of(
					provider = entry.provider,
					model = entry.model,
					inputPerMtok = entry.inputPerMtok,
					outputPerMtok = entry.outputPerMtok,
					cachedInputPerMtok = entry.cachedInputPerMtok,
					currency = entry.currency,
					effectiveFrom = entry.effectiveFrom,
				)
			} catch (e: BadRequestException) {
				throw PriceFileException("prices[$index]: ${e.message}", e)
			}
		}
	}
}
