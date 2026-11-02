package tokentollgate.usage

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tokentollgate.db.Outcome
import tokentollgate.db.Protocol
import tokentollgate.db.UsageRecord
import tokentollgate.db.UsageRecordRepository
import tokentollgate.db.UsageSource
import tokentollgate.pricing.CostResult
import tokentollgate.pricing.PricingService
import tokentollgate.pricing.TokenUsage
import tokentollgate.security.AuthenticatedKey
import java.time.Instant
import java.util.UUID

/** Everything known about a finished request; the cost is computed while saving. */
data class UsageDraft(
	val requestId: UUID,
	val startedAt: Instant,
	val key: AuthenticatedKey,
	val protocol: Protocol,
	val provider: String,
	val modelRequested: String,
	val modelServed: String?,
	val usage: TokenUsage?,
	val usageSource: UsageSource,
	val ttftMs: Int?,
	val durationMs: Int,
	val httpStatus: Int,
	val streamed: Boolean,
	val outcome: Outcome,
	val errorType: String?,
)

/** Saves one `usage_records` row per request off the response path; a failure to save is logged and never reaches the client. */
@Component
class UsageRecorder(private val records: UsageRecordRepository, private val pricing: PricingService) {

	private val log = LoggerFactory.getLogger(UsageRecorder::class.java)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("usage-recorder"))

	fun record(draft: UsageDraft) {
		scope.launch {
			try {
				save(draft)
			} catch (e: Exception) {
				log.warn("Failed to record usage for request {}", draft.requestId, e)
			}
		}
	}

	/** Prices by the model the upstream reported, falling back to the model the client asked for. */
	suspend fun save(draft: UsageDraft): UsageRecord {
		val priceModel = draft.modelServed ?: draft.modelRequested
		val cost = draft.usage?.let { pricing.cost(draft.provider, priceModel, draft.startedAt, it) } as? CostResult.Priced
		val record = UsageRecord(
			requestId = draft.requestId,
			startedAt = draft.startedAt,
			keyId = draft.key.keyId,
			projectId = draft.key.projectId,
			tag = draft.key.tag,
			protocol = draft.protocol,
			provider = draft.provider,
			modelRequested = draft.modelRequested,
			modelServed = draft.modelServed,
			inputTokens = draft.usage?.inputTokens ?: 0,
			outputTokens = draft.usage?.outputTokens ?: 0,
			cachedInputTokens = draft.usage?.cachedInputTokens ?: 0,
			cost = cost?.cost,
			priceId = cost?.priceId,
			usageSource = draft.usageSource,
			ttftMs = draft.ttftMs,
			durationMs = draft.durationMs,
			httpStatus = draft.httpStatus,
			streamed = draft.streamed,
			outcome = draft.outcome,
			errorType = draft.errorType,
		)
		val saved = records.save(record)
		log.info(
			"relay request_id={} project={} key={} protocol={} provider={} model={} served={} stream={} status={} outcome={} error={} tokens={}/{}/{} cost={} ttft_ms={} duration_ms={}",
			saved.requestId, saved.projectId, draft.key.keyPrefix, saved.protocol.dbValue, saved.provider, saved.modelRequested, saved.modelServed,
			saved.streamed, saved.httpStatus, saved.outcome.dbValue, saved.errorType, saved.inputTokens, saved.cachedInputTokens, saved.outputTokens,
			saved.cost?.toPlainString(), saved.ttftMs, saved.durationMs,
		)
		return saved
	}
}
