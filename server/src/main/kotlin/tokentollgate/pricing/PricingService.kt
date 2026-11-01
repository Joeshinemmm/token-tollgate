package tokentollgate.pricing

import kotlinx.coroutines.flow.toList
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import tokentollgate.common.ConflictException
import tokentollgate.common.NotFoundException
import tokentollgate.db.ModelPrice
import tokentollgate.db.ModelPriceRepository
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

sealed class CostResult {
	data class Priced(val cost: BigDecimal, val priceId: UUID) : CostResult()

	/** No price row covers the model at that time; the usage is recorded with `cost` and `price_id` null. */
	data object Unpriced : CostResult()
}

data class UpsertResult(val price: ModelPrice, val created: Boolean)

data class ImportResult(val inserted: Int, val skipped: Int)

@Service
class PricingService(private val prices: ModelPriceRepository) {

	/** The price in force for [provider]/[model] at [at]: the latest row whose `effective_from` is not after [at]. */
	suspend fun priceFor(provider: String, model: String, at: Instant): ModelPrice? =
		prices.findEffective(provider, model, at)

	suspend fun cost(provider: String, model: String, at: Instant, usage: TokenUsage): CostResult {
		val price = priceFor(provider, model, at) ?: return CostResult.Unpriced
		return CostResult.Priced(CostCalculator.cost(price, usage), requireNotNull(price.id))
	}

	suspend fun list(provider: String? = null, model: String? = null): List<ModelPrice> =
		prices.findAllByOrderByProviderAscModelAscEffectiveFromDesc().toList()
			.filter { provider == null || it.provider == provider }
			.filter { model == null || it.model == model }

	/** Creates the row for (provider, model, effectiveFrom) or replaces the rates of the existing one. */
	suspend fun upsert(spec: PriceSpec): UpsertResult {
		val existing = prices.findByProviderAndModelAndEffectiveFrom(spec.provider, spec.model, spec.effectiveFrom)
			?: return UpsertResult(prices.save(spec.toEntity()), created = true)
		val updated = existing.copy(
			inputPerMtok = spec.inputPerMtok,
			outputPerMtok = spec.outputPerMtok,
			cachedInputPerMtok = spec.cachedInputPerMtok,
			currency = spec.currency,
		)
		return UpsertResult(prices.save(updated), created = false)
	}

	suspend fun delete(id: UUID) {
		if (!prices.existsById(id)) throw NotFoundException("Price not found: $id")
		try {
			prices.deleteById(id)
		} catch (e: DataIntegrityViolationException) {
			throw ConflictException("Price is referenced by usage records; add a row with a later effectiveFrom instead")
		}
	}

	/**
	 * Inserts the file's rows that are not in the table yet, keyed by (provider, model, effectiveFrom).
	 * Rows already present are left unchanged, so the file is a seed, not a source of truth.
	 * The whole file is parsed and validated before anything is written.
	 */
	suspend fun importFile(path: Path): ImportResult {
		val specs = PriceFileParser.parse(path)
		var inserted = 0
		var skipped = 0
		for (spec in specs) {
			val existing = prices.findByProviderAndModelAndEffectiveFrom(spec.provider, spec.model, spec.effectiveFrom)
			if (existing == null) {
				prices.save(spec.toEntity())
				inserted++
			} else {
				skipped++
			}
		}
		return ImportResult(inserted, skipped)
	}
}
