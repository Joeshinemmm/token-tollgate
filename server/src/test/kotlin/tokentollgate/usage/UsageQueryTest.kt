package tokentollgate.usage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tokentollgate.common.BadRequestException
import tokentollgate.db.Outcome
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageQueryTest {

	private val now = Instant.parse("2026-11-06T03:00:00Z")

	@Test
	fun `instants are taken as given and dates are midnight in the requested zone`() {
		val utc = UsageWindow.of("2026-11-04T15:00:00Z", "2026-11-05", null, now)
		assertEquals(Instant.parse("2026-11-04T15:00:00Z"), utc.from)
		assertEquals(Instant.parse("2026-11-05T00:00:00Z"), utc.to)
		assertEquals(ZoneId.of("UTC").normalized(), utc.zone.normalized())

		val seoul = UsageWindow.of("2026-11-05", "2026-11-06", "Asia/Seoul", now)
		assertEquals(Instant.parse("2026-11-04T15:00:00Z"), seoul.from)
		assertEquals(Instant.parse("2026-11-05T15:00:00Z"), seoul.to)
		assertEquals(ZoneId.of("Asia/Seoul"), seoul.zone)

		val offset = UsageWindow.of("2026-11-05T09:00:00+09:00", null, "Asia/Seoul", now)
		assertEquals(Instant.parse("2026-11-05T00:00:00Z"), offset.from)
	}

	@Test
	fun `missing bounds default to the last seven days ending now`() {
		val window = UsageWindow.of(null, null, null, now)
		assertEquals(now, window.to)
		assertEquals(now.minus(Duration.ofDays(7)), window.from)
	}

	@Test
	fun `bad bounds, zones and grouping are rejected`() {
		assertEquals("from must be before to", assertThrows<BadRequestException> { UsageWindow.of("2026-11-05", "2026-11-05", null, now) }.message)
		assertEquals("from must be before to", assertThrows<BadRequestException> { UsageWindow.of("2026-11-06", "2026-11-05", null, now) }.message)
		assertEquals("from and to must be at most 366 days apart", assertThrows<BadRequestException> { UsageWindow.of("2025-01-01", "2026-06-01", null, now) }.message)
		assertEquals("tz must be a valid time zone id such as Asia/Seoul", assertThrows<BadRequestException> { UsageWindow.of(null, null, "Mars/Olympus", now) }.message)
		assertEquals("from must be a date (YYYY-MM-DD) or an ISO-8601 instant", assertThrows<BadRequestException> { UsageWindow.of("yesterday", null, null, now) }.message)
		assertEquals("groupBy must be one of day, project, model, tag", assertThrows<BadRequestException> { GroupBy.parse("hour") }.message)
		assertEquals(GroupBy.DAY, GroupBy.parse(null))
		assertEquals(GroupBy.MODEL, GroupBy.parse("model"))
	}

	@Test
	fun `filters and limits are parsed and bounded`() {
		val id = UUID.randomUUID()
		val filter = UsageFilter.of(id.toString(), " alpha ", "", null, "upstream_error")
		assertEquals(id, filter.projectId)
		assertEquals(" alpha ", filter.tag)
		assertNull(filter.model)
		assertEquals(Outcome.UPSTREAM_ERROR, filter.outcome)
		assertEquals("projectId must be a UUID", assertThrows<BadRequestException> { UsageFilter.of("nope", null, null, null) }.message)
		assertEquals("outcome must be one of ok, upstream_error, client_abort, timeout, gateway_error", assertThrows<BadRequestException> { UsageFilter.of(null, null, null, null, "exploded") }.message)
		assertEquals(50, RecordsQuery.limit(null))
		assertEquals(200, RecordsQuery.limit(200))
		assertEquals("limit must be between 1 and 200", assertThrows<BadRequestException> { RecordsQuery.limit(0) }.message)
		assertEquals("limit must be between 1 and 200", assertThrows<BadRequestException> { RecordsQuery.limit(201) }.message)
	}
}
