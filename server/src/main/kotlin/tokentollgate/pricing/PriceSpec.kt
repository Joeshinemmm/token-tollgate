package tokentollgate.pricing

import tokentollgate.common.BadRequestException
import tokentollgate.db.ModelPrice
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** One validated price row, whether it came from the admin API or from the price file. */
data class PriceSpec(
	val provider: String,
	val model: String,
	val inputPerMtok: BigDecimal,
	val outputPerMtok: BigDecimal,
	val cachedInputPerMtok: BigDecimal?,
	val currency: String,
	val effectiveFrom: Instant,
) {
	fun toEntity(): ModelPrice = ModelPrice(
		provider = provider,
		model = model,
		inputPerMtok = inputPerMtok,
		outputPerMtok = outputPerMtok,
		cachedInputPerMtok = cachedInputPerMtok,
		currency = currency,
		effectiveFrom = effectiveFrom,
	)

	companion object {
		const val CURRENCY = "USD"
		const val MAX_NAME_LENGTH = 200
		const val RATE_SCALE = 8
		private val MAX_RATE = BigDecimal("10000000000") // numeric(18, 8) holds ten integer digits
		private const val DATE_LENGTH = 10 // YYYY-MM-DD

		/** Validates raw input. Every failure is a [BadRequestException] whose message names the field. */
		fun of(
			provider: String?,
			model: String?,
			inputPerMtok: BigDecimal?,
			outputPerMtok: BigDecimal?,
			cachedInputPerMtok: BigDecimal?,
			currency: String?,
			effectiveFrom: String?,
		): PriceSpec = PriceSpec(
			provider = name("provider", provider),
			model = name("model", model),
			inputPerMtok = rate("inputPerMtok", inputPerMtok) ?: throw BadRequestException("inputPerMtok is required"),
			outputPerMtok = rate("outputPerMtok", outputPerMtok) ?: throw BadRequestException("outputPerMtok is required"),
			cachedInputPerMtok = rate("cachedInputPerMtok", cachedInputPerMtok),
			currency = currency(currency),
			effectiveFrom = effectiveFrom(effectiveFrom),
		)

		private fun name(field: String, value: String?): String {
			val clean = value?.trim().orEmpty()
			if (clean.isEmpty()) throw BadRequestException("$field must not be blank")
			if (clean.length > MAX_NAME_LENGTH) throw BadRequestException("$field must be at most $MAX_NAME_LENGTH characters")
			return clean
		}

		private fun rate(field: String, value: BigDecimal?): BigDecimal? {
			if (value == null) return null
			if (value.signum() < 0) throw BadRequestException("$field must not be negative")
			if (value >= MAX_RATE) throw BadRequestException("$field is too large")
			val stripped = value.stripTrailingZeros()
			if (stripped.scale() > RATE_SCALE) throw BadRequestException("$field must have at most $RATE_SCALE decimal places")
			return stripped.setScale(RATE_SCALE)
		}

		private fun currency(value: String?): String {
			val clean = value?.trim().orEmpty().ifEmpty { CURRENCY }
			if (clean != CURRENCY) throw BadRequestException("currency must be $CURRENCY")
			return clean
		}

		/** Accepts a date (`2026-11-01`, taken as midnight UTC) or an ISO-8601 instant (`2026-11-01T09:00:00Z`). */
		fun effectiveFrom(value: String?): Instant {
			val clean = value?.trim().orEmpty()
			if (clean.isEmpty()) throw BadRequestException("effectiveFrom is required")
			return try {
				if (clean.length == DATE_LENGTH) LocalDate.parse(clean).atStartOfDay(ZoneOffset.UTC).toInstant()
				else Instant.parse(clean).truncatedTo(ChronoUnit.MICROS)
			} catch (e: DateTimeParseException) {
				throw BadRequestException("effectiveFrom must be a date (YYYY-MM-DD) or an ISO-8601 instant")
			}
		}
	}
}
