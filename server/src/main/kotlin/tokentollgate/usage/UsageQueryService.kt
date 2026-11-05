package tokentollgate.usage

import org.springframework.stereotype.Service
import java.math.BigDecimal

/** The result of a summary query: the buckets in display order and their total. */
data class UsageSummary(val query: SummaryQuery, val totals: UsageBucket, val groups: List<UsageBucket>)

/** A page of records plus the cursor for the next one, or null at the end. */
data class UsagePage(val records: List<UsageRecordRow>, val nextBefore: Long?)

@Service
class UsageQueryService(private val queries: UsageQueries) {

	/** Days ascend by date; the other groupings put the most expensive bucket first. Totals are the sum of the buckets. */
	suspend fun summary(query: SummaryQuery): UsageSummary {
		val buckets = queries.summary(query)
		val ordered = when (query.groupBy) {
			GroupBy.DAY -> buckets.sortedBy { it.key }
			else -> buckets.sortedWith(compareByDescending<UsageBucket> { it.cost }.thenByDescending { it.requests }.thenBy { it.key ?: "" })
		}
		return UsageSummary(query, totals = total(buckets), groups = ordered)
	}

	suspend fun records(query: RecordsQuery): UsagePage {
		val rows = queries.records(query)
		val nextBefore = if (rows.size == query.limit) rows.last().id else null
		return UsagePage(rows, nextBefore)
	}

	private fun total(buckets: List<UsageBucket>) = UsageBucket(
		key = null,
		label = null,
		provider = null,
		requests = buckets.sumOf { it.requests },
		okRequests = buckets.sumOf { it.okRequests },
		inputTokens = buckets.sumOf { it.inputTokens },
		cachedInputTokens = buckets.sumOf { it.cachedInputTokens },
		outputTokens = buckets.sumOf { it.outputTokens },
		cost = buckets.fold(BigDecimal.ZERO) { acc, bucket -> acc + bucket.cost },
		priceMissingRequests = buckets.sumOf { it.priceMissingRequests },
		usageUnknownRequests = buckets.sumOf { it.usageUnknownRequests },
	)
}
