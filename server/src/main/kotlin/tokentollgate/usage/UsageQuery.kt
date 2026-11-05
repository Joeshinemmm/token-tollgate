package tokentollgate.usage

import tokentollgate.common.BadRequestException
import tokentollgate.db.Outcome
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/** How `summary` groups the records. */
enum class GroupBy(val param: String) {
	DAY("day"), PROJECT("project"), MODEL("model"), TAG("tag");

	companion object {
		fun parse(value: String?): GroupBy {
			if (value.isNullOrBlank()) return DAY
			return entries.firstOrNull { it.param == value.trim() }
				?: throw BadRequestException("groupBy must be one of ${entries.joinToString(", ") { it.param }}")
		}
	}
}

/** The time range of a query: `from` inclusive, `to` exclusive, plus the zone used for day buckets and date-only bounds. */
data class UsageWindow(val from: Instant, val to: Instant, val zone: ZoneId) {

	companion object {
		val DEFAULT_RANGE: Duration = Duration.ofDays(7)
		val MAX_RANGE: Duration = Duration.ofDays(366)

		fun of(from: String?, to: String?, tz: String?, now: Instant = Instant.now()): UsageWindow {
			val zone = parseZone(tz)
			val toInstant = to?.takeIf { it.isNotBlank() }?.let { parseTime("to", it, zone) } ?: now
			val fromInstant = from?.takeIf { it.isNotBlank() }?.let { parseTime("from", it, zone) } ?: toInstant.minus(DEFAULT_RANGE)
			if (!fromInstant.isBefore(toInstant)) throw BadRequestException("from must be before to")
			if (Duration.between(fromInstant, toInstant) > MAX_RANGE) throw BadRequestException("from and to must be at most ${MAX_RANGE.toDays()} days apart")
			return UsageWindow(fromInstant, toInstant, zone)
		}

		fun parseZone(tz: String?): ZoneId {
			if (tz.isNullOrBlank()) return ZoneId.of("UTC")
			return try {
				ZoneId.of(tz.trim())
			} catch (e: DateTimeException) {
				throw BadRequestException("tz must be a valid time zone id such as Asia/Seoul")
			}
		}

		/** A 10-character value is a date at midnight in [zone]; anything else must be an ISO-8601 instant or offset date-time. */
		fun parseTime(name: String, value: String, zone: ZoneId): Instant {
			val trimmed = value.trim()
			try {
				if (trimmed.length == 10) return LocalDate.parse(trimmed).atStartOfDay(zone).toInstant()
				return try {
					Instant.parse(trimmed)
				} catch (e: DateTimeParseException) {
					OffsetDateTime.parse(trimmed).toInstant()
				}
			} catch (e: DateTimeParseException) {
				throw BadRequestException("$name must be a date (YYYY-MM-DD) or an ISO-8601 instant")
			}
		}
	}
}

/** Optional filters shared by both queries; a null field means "no filter". */
data class UsageFilter(
	val projectId: UUID? = null,
	val tag: String? = null,
	val model: String? = null,
	val provider: String? = null,
	val outcome: Outcome? = null,
) {
	companion object {
		fun of(projectId: String?, tag: String?, model: String?, provider: String?, outcome: String? = null) = UsageFilter(
			projectId = projectId?.takeIf { it.isNotBlank() }?.let { parseProjectId(it) },
			tag = tag?.takeIf { it.isNotBlank() },
			model = model?.takeIf { it.isNotBlank() },
			provider = provider?.takeIf { it.isNotBlank() },
			outcome = outcome?.takeIf { it.isNotBlank() }?.let { parseOutcome(it) },
		)

		private fun parseProjectId(value: String): UUID = try {
			UUID.fromString(value.trim())
		} catch (e: IllegalArgumentException) {
			throw BadRequestException("projectId must be a UUID")
		}

		private fun parseOutcome(value: String): Outcome = Outcome.entries.firstOrNull { it.dbValue == value.trim() }
			?: throw BadRequestException("outcome must be one of ${Outcome.entries.joinToString(", ") { it.dbValue }}")
	}
}

data class SummaryQuery(val window: UsageWindow, val groupBy: GroupBy, val filter: UsageFilter)

data class RecordsQuery(val window: UsageWindow, val filter: UsageFilter, val limit: Int, val before: Long?) {
	companion object {
		const val DEFAULT_LIMIT = 50
		const val MAX_LIMIT = 200

		fun limit(value: Int?): Int {
			val limit = value ?: DEFAULT_LIMIT
			if (limit < 1 || limit > MAX_LIMIT) throw BadRequestException("limit must be between 1 and $MAX_LIMIT")
			return limit
		}
	}
}
