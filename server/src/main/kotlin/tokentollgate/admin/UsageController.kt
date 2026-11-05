package tokentollgate.admin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tokentollgate.usage.GroupBy
import tokentollgate.usage.RecordsQuery
import tokentollgate.usage.SummaryQuery
import tokentollgate.usage.UsageFilter
import tokentollgate.usage.UsageQueryService
import tokentollgate.usage.UsageWindow

/** Read-only usage views for the dashboard. Times are stored in UTC; `tz` only decides day buckets and date-only bounds. */
@RestController
@RequestMapping("/admin/v1/usage")
class UsageController(private val service: UsageQueryService) {

	@GetMapping("/summary")
	suspend fun summary(
		@RequestParam(required = false) from: String?,
		@RequestParam(required = false) to: String?,
		@RequestParam(required = false) groupBy: String?,
		@RequestParam(required = false) tz: String?,
		@RequestParam(required = false) projectId: String?,
		@RequestParam(required = false) tag: String?,
		@RequestParam(required = false) model: String?,
		@RequestParam(required = false) provider: String?,
	): UsageSummaryResponse {
		val query = SummaryQuery(
			window = UsageWindow.of(from, to, tz),
			groupBy = GroupBy.parse(groupBy),
			filter = UsageFilter.of(projectId, tag, model, provider),
		)
		return UsageSummaryResponse.from(service.summary(query))
	}

	@GetMapping("/records")
	suspend fun records(
		@RequestParam(required = false) from: String?,
		@RequestParam(required = false) to: String?,
		@RequestParam(required = false) tz: String?,
		@RequestParam(required = false) projectId: String?,
		@RequestParam(required = false) tag: String?,
		@RequestParam(required = false) model: String?,
		@RequestParam(required = false) provider: String?,
		@RequestParam(required = false) outcome: String?,
		@RequestParam(required = false) limit: Int?,
		@RequestParam(required = false) before: Long?,
	): UsageRecordsResponse {
		val query = RecordsQuery(
			window = UsageWindow.of(from, to, tz),
			filter = UsageFilter.of(projectId, tag, model, provider, outcome),
			limit = RecordsQuery.limit(limit),
			before = before,
		)
		return UsageRecordsResponse.from(service.records(query))
	}
}
