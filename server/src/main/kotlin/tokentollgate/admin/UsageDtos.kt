package tokentollgate.admin

import tokentollgate.usage.UsageBucket
import tokentollgate.usage.UsagePage
import tokentollgate.usage.UsageRecordRow
import tokentollgate.usage.UsageSummary
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Totals of a period: requests including failures, and how many of them have no cost for which reason. */
data class UsageMetrics(
	val requests: Long,
	val okRequests: Long,
	val inputTokens: Long,
	val cachedInputTokens: Long,
	val outputTokens: Long,
	val cost: BigDecimal,
	val priceMissingRequests: Long,
	val usageUnknownRequests: Long,
) {
	companion object {
		fun from(bucket: UsageBucket) = UsageMetrics(
			requests = bucket.requests,
			okRequests = bucket.okRequests,
			inputTokens = bucket.inputTokens,
			cachedInputTokens = bucket.cachedInputTokens,
			outputTokens = bucket.outputTokens,
			cost = bucket.cost,
			priceMissingRequests = bucket.priceMissingRequests,
			usageUnknownRequests = bucket.usageUnknownRequests,
		)
	}
}

/** One bucket: `key` is the day, project id, model or tag (null for records without a tag); `label` is the project name. */
data class UsageGroupResponse(
	val key: String?,
	val label: String?,
	val provider: String?,
	val requests: Long,
	val okRequests: Long,
	val inputTokens: Long,
	val cachedInputTokens: Long,
	val outputTokens: Long,
	val cost: BigDecimal,
	val priceMissingRequests: Long,
	val usageUnknownRequests: Long,
) {
	companion object {
		fun from(bucket: UsageBucket) = UsageGroupResponse(
			key = bucket.key,
			label = bucket.label,
			provider = bucket.provider,
			requests = bucket.requests,
			okRequests = bucket.okRequests,
			inputTokens = bucket.inputTokens,
			cachedInputTokens = bucket.cachedInputTokens,
			outputTokens = bucket.outputTokens,
			cost = bucket.cost,
			priceMissingRequests = bucket.priceMissingRequests,
			usageUnknownRequests = bucket.usageUnknownRequests,
		)
	}
}

data class UsageSummaryResponse(
	val from: Instant,
	val to: Instant,
	val tz: String,
	val groupBy: String,
	val totals: UsageMetrics,
	val groups: List<UsageGroupResponse>,
) {
	companion object {
		fun from(summary: UsageSummary) = UsageSummaryResponse(
			from = summary.query.window.from,
			to = summary.query.window.to,
			tz = summary.query.window.zone.id,
			groupBy = summary.query.groupBy.param,
			totals = UsageMetrics.from(summary.totals),
			groups = summary.groups.map(UsageGroupResponse::from),
		)
	}
}

data class UsageRecordResponse(
	val id: Long,
	val requestId: UUID,
	val startedAt: Instant,
	val projectId: UUID,
	val projectName: String?,
	val keyId: UUID,
	val keyPrefix: String?,
	val tag: String?,
	val protocol: String,
	val provider: String,
	val modelRequested: String,
	val modelServed: String?,
	val inputTokens: Int,
	val cachedInputTokens: Int,
	val outputTokens: Int,
	val cost: BigDecimal?,
	val usageSource: String,
	val ttftMs: Int?,
	val durationMs: Int,
	val httpStatus: Int,
	val streamed: Boolean,
	val outcome: String,
	val errorType: String?,
) {
	companion object {
		fun from(row: UsageRecordRow) = UsageRecordResponse(
			id = row.id,
			requestId = row.requestId,
			startedAt = row.startedAt.toInstant(),
			projectId = row.projectId,
			projectName = row.projectName,
			keyId = row.keyId,
			keyPrefix = row.keyPrefix,
			tag = row.tag,
			protocol = row.protocol,
			provider = row.provider,
			modelRequested = row.modelRequested,
			modelServed = row.modelServed,
			inputTokens = row.inputTokens,
			cachedInputTokens = row.cachedInputTokens,
			outputTokens = row.outputTokens,
			cost = row.cost,
			usageSource = row.usageSource,
			ttftMs = row.ttftMs,
			durationMs = row.durationMs,
			httpStatus = row.httpStatus,
			streamed = row.streamed,
			outcome = row.outcome,
			errorType = row.errorType,
		)
	}
}

data class UsageRecordsResponse(val records: List<UsageRecordResponse>, val nextBefore: Long?) {
	companion object {
		fun from(page: UsagePage) = UsageRecordsResponse(page.records.map(UsageRecordResponse::from), page.nextBefore)
	}
}
