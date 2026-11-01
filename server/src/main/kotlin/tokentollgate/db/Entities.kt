package tokentollgate.db

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Current time truncated to microseconds, the precision PostgreSQL stores for timestamptz. */
fun dbNow(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

@Table("projects")
data class Project(
	@Id val id: UUID? = null,
	val name: String,
	val createdAt: Instant = dbNow(),
	val archivedAt: Instant? = null,
)

@Table("virtual_keys")
data class VirtualKey(
	@Id val id: UUID? = null,
	val projectId: UUID,
	val name: String,
	val keyPrefix: String,
	val keyHash: String,
	val createdAt: Instant = dbNow(),
	val revokedAt: Instant? = null,
	val lastUsedAt: Instant? = null,
)

@Table("model_prices")
data class ModelPrice(
	@Id val id: UUID? = null,
	val provider: String,
	val model: String,
	val inputPerMtok: BigDecimal,
	val outputPerMtok: BigDecimal,
	val cachedInputPerMtok: BigDecimal? = null,
	val currency: String = "USD",
	val effectiveFrom: Instant,
	val createdAt: Instant = dbNow(),
)

@Table("usage_records")
data class UsageRecord(
	@Id val id: Long? = null,
	val requestId: UUID,
	val startedAt: Instant,
	val keyId: UUID,
	val projectId: UUID,
	val tag: String? = null,
	val protocol: Protocol,
	val provider: String,
	val modelRequested: String,
	val modelServed: String? = null,
	val inputTokens: Int = 0,
	val outputTokens: Int = 0,
	val cachedInputTokens: Int = 0,
	val cost: BigDecimal? = null,
	val priceId: UUID? = null,
	val usageSource: UsageSource,
	val ttftMs: Int? = null,
	val durationMs: Int,
	val httpStatus: Int,
	val streamed: Boolean,
	val outcome: Outcome,
	val errorType: String? = null,
)
