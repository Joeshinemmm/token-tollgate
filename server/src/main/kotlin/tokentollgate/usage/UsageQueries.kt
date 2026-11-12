package tokentollgate.usage

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** One aggregated bucket; the totals are the same shape with no key. */
data class UsageBucket(
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
)

/** One usage record joined with its project name and key prefix. Enum columns stay as their text values. */
data class UsageRecordRow(
	val id: Long,
	val requestId: UUID,
	val startedAt: OffsetDateTime,
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
)

/**
 * The two read queries behind the usage API. The SQL is assembled from fixed fragments only; every value a caller
 * supplies, including the time zone name, travels as a bind parameter.
 */
@Component
class UsageQueries(private val client: DatabaseClient) {

	suspend fun summary(query: SummaryQuery): List<UsageBucket> {
		val keyColumns = when (query.groupBy) {
			GroupBy.DAY -> "to_char(r.started_at AT TIME ZONE :tz, 'YYYY-MM-DD') AS key, NULL::text AS label, NULL::text AS provider"
			GroupBy.PROJECT -> "r.project_id::text AS key, p.name AS label, NULL::text AS provider"
			GroupBy.MODEL -> "COALESCE(r.model_served, r.model_requested) AS key, NULL::text AS label, r.provider AS provider"
			GroupBy.TAG -> "r.tag AS key, NULL::text AS label, NULL::text AS provider"
		}
		val join = if (query.groupBy == GroupBy.PROJECT) " LEFT JOIN projects p ON p.id = r.project_id" else ""
		val (where, binds) = where(query.window, query.filter, before = null)
		val sql = """
			SELECT $keyColumns,
			       COUNT(*) AS requests,
			       COUNT(*) FILTER (WHERE r.outcome = 'ok') AS ok_requests,
			       COALESCE(SUM(r.input_tokens), 0) AS input_tokens,
			       COALESCE(SUM(r.cached_input_tokens), 0) AS cached_input_tokens,
			       COALESCE(SUM(r.output_tokens), 0) AS output_tokens,
			       SUM(r.cost) AS cost,
			       COUNT(*) FILTER (WHERE r.cost IS NULL AND r.usage_source <> 'none') AS price_missing,
			       COUNT(*) FILTER (WHERE r.usage_source = 'none') AS usage_unknown
			FROM usage_records r$join
			$where
			GROUP BY 1, 2, 3
		""".trimIndent()
		var spec = client.sql(sql)
		if (query.groupBy == GroupBy.DAY) spec = spec.bind("tz", query.window.zone.id)
		binds.forEach { (name, value) -> spec = spec.bind(name, value) }
		return spec.map { row -> bucket(row) }.all().asFlow().toList()
	}

	suspend fun records(query: RecordsQuery): List<UsageRecordRow> {
		val (where, binds) = where(query.window, query.filter, query.before)
		val sql = """
			SELECT r.id, r.request_id, r.started_at, r.key_id, r.project_id, p.name AS project_name, k.key_prefix, r.tag,
			       r.protocol, r.provider, r.model_requested, r.model_served, r.input_tokens, r.cached_input_tokens, r.output_tokens,
			       r.cost, r.usage_source, r.ttft_ms, r.duration_ms, r.http_status, r.streamed, r.outcome, r.error_type
			FROM usage_records r
			LEFT JOIN projects p ON p.id = r.project_id
			LEFT JOIN virtual_keys k ON k.id = r.key_id
			$where
			ORDER BY r.id DESC
			LIMIT :limit
		""".trimIndent()
		var spec = client.sql(sql).bind("limit", query.limit)
		binds.forEach { (name, value) -> spec = spec.bind(name, value) }
		return spec.map { row -> record(row) }.all().asFlow().toList()
	}

	/** The WHERE clause and the binds it needs; a filter that is null adds no condition. */
	private fun where(window: UsageWindow, filter: UsageFilter, before: Long?): Pair<String, Map<String, Any>> {
		val conditions = mutableListOf("r.started_at >= :from", "r.started_at < :to")
		val binds = mutableMapOf<String, Any>("from" to window.from, "to" to window.to)
		filter.projectId?.let { conditions += "r.project_id = :projectId"; binds["projectId"] = it }
		filter.tag?.let { conditions += "r.tag = :tag"; binds["tag"] = it }
		filter.model?.let { conditions += "COALESCE(r.model_served, r.model_requested) = :model"; binds["model"] = it }
		filter.provider?.let { conditions += "r.provider = :provider"; binds["provider"] = it }
		filter.outcome?.let { conditions += "r.outcome = :outcome"; binds["outcome"] = it.dbValue }
		before?.let { conditions += "r.id < :before"; binds["before"] = it }
		return "WHERE " + conditions.joinToString(" AND ") to binds
	}

	private fun bucket(row: Readable) = UsageBucket(
		key = row.get("key", String::class.java),
		label = row.get("label", String::class.java),
		provider = row.get("provider", String::class.java),
		requests = row.long("requests"),
		okRequests = row.long("ok_requests"),
		inputTokens = row.long("input_tokens"),
		cachedInputTokens = row.long("cached_input_tokens"),
		outputTokens = row.long("output_tokens"),
		cost = row.get("cost", BigDecimal::class.java) ?: BigDecimal.ZERO,
		priceMissingRequests = row.long("price_missing"),
		usageUnknownRequests = row.long("usage_unknown"),
	)

	private fun record(row: Readable) = UsageRecordRow(
		id = row.long("id"),
		requestId = requireNotNull(row.get("request_id", UUID::class.java)),
		startedAt = requireNotNull(row.get("started_at", OffsetDateTime::class.java)),
		projectId = requireNotNull(row.get("project_id", UUID::class.java)),
		projectName = row.get("project_name", String::class.java),
		keyId = requireNotNull(row.get("key_id", UUID::class.java)),
		keyPrefix = row.get("key_prefix", String::class.java),
		tag = row.get("tag", String::class.java),
		protocol = requireNotNull(row.get("protocol", String::class.java)),
		provider = requireNotNull(row.get("provider", String::class.java)),
		modelRequested = requireNotNull(row.get("model_requested", String::class.java)),
		modelServed = row.get("model_served", String::class.java),
		inputTokens = row.int("input_tokens"),
		cachedInputTokens = row.int("cached_input_tokens"),
		outputTokens = row.int("output_tokens"),
		cost = row.get("cost", BigDecimal::class.java),
		usageSource = requireNotNull(row.get("usage_source", String::class.java)),
		ttftMs = row.get("ttft_ms", Int::class.javaObjectType),
		durationMs = row.int("duration_ms"),
		httpStatus = row.int("http_status"),
		streamed = requireNotNull(row.get("streamed", Boolean::class.javaObjectType)),
		outcome = requireNotNull(row.get("outcome", String::class.java)),
		errorType = row.get("error_type", String::class.java),
	)

	private fun Readable.long(name: String): Long = requireNotNull(get(name, Long::class.javaObjectType))

	private fun Readable.int(name: String): Int = requireNotNull(get(name, Int::class.javaObjectType))
}
