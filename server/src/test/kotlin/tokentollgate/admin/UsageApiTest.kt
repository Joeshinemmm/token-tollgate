package tokentollgate.admin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import tokentollgate.createProject
import tokentollgate.db.Outcome
import tokentollgate.db.Protocol
import tokentollgate.db.UsageRecord
import tokentollgate.db.UsageRecordRepository
import tokentollgate.db.UsageSource
import tokentollgate.issueKey
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Eight hand-made records in March 2025, well away from the timestamps other tests write, across two projects,
 * two models, two tags (and none), one record without a price and one failed request without usage.
 * Two of the timestamps straddle midnight in Seoul but not in UTC.
 */
@IntegrationTest
class UsageApiTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var records: UsageRecordRepository

	private val evening = Instant.parse("2025-03-01T14:30:00Z")
	private val afterMidnightInSeoul = Instant.parse("2025-03-01T15:30:00Z")
	private val window = "from=2025-03-01&to=2025-03-03"

	companion object {
		private var seeded = false
		private lateinit var projectA: UUID
		private lateinit var projectB: UUID
		private lateinit var projectAName: String
		private lateinit var keyPrefixA: String
		private lateinit var ids: List<Long>
	}

	@BeforeEach
	fun seed() = runBlocking {
		if (seeded) return@runBlocking
		val a = client.createProject("usage-api-a-${UUID.randomUUID()}")
		val b = client.createProject("usage-api-b-${UUID.randomUUID()}")
		val keyA = client.issueKey(a.id)
		val keyB = client.issueKey(b.id)
		projectA = a.id
		projectB = b.id
		projectAName = a.name
		keyPrefixA = keyA.keyPrefix
		val rows = listOf(
			record(a.id, keyA.id, evening, "fake", "fake-small", "alpha", 100, 0, 10, "0.00010000", UsageSource.RESPONSE),
			record(a.id, keyA.id, evening, "fake", "fake-small", "alpha", 200, 50, 20, "0.00020000", UsageSource.STREAM, streamed = true, ttftMs = 12),
			record(a.id, keyA.id, afterMidnightInSeoul, "fake", "fake-large", "beta", 300, 0, 30, "0.00300000", UsageSource.RESPONSE),
			record(a.id, keyA.id, afterMidnightInSeoul, "fake", "fake-large", null, 400, 100, 40, "0.00400000", UsageSource.RESPONSE),
			record(b.id, keyB.id, evening, "fake", "fake-small", "alpha", 50, 0, 5, "0.00005000", UsageSource.RESPONSE),
			record(b.id, keyB.id, afterMidnightInSeoul, "openai", "gpt-x", "beta", 60, 0, 6, null, UsageSource.RESPONSE, modelServed = null),
			record(b.id, keyB.id, afterMidnightInSeoul, "fake", "fake-small", null, 0, 0, 0, null, UsageSource.NONE, outcome = Outcome.UPSTREAM_ERROR, errorType = "rate_limit_error", httpStatus = 429),
			record(b.id, keyB.id, afterMidnightInSeoul, "fake", "fake-small", "beta", 70, 10, 7, "0.00007000", UsageSource.STREAM, streamed = true, ttftMs = 8),
		)
		ids = rows.map { records.save(it).id!! }
		seeded = true
	}

	private fun record(
		projectId: UUID, keyId: UUID, startedAt: Instant, provider: String, model: String, tag: String?,
		input: Int, cached: Int, output: Int, cost: String?, source: UsageSource,
		modelServed: String? = model, streamed: Boolean = false, ttftMs: Int? = null,
		outcome: Outcome = Outcome.OK, errorType: String? = null, httpStatus: Int = 200,
	) = UsageRecord(
		requestId = UUID.randomUUID(), startedAt = startedAt, keyId = keyId, projectId = projectId, tag = tag,
		protocol = Protocol.OPENAI, provider = provider, modelRequested = model, modelServed = modelServed,
		inputTokens = input, outputTokens = output, cachedInputTokens = cached, cost = cost?.let(::BigDecimal), priceId = null,
		usageSource = source, ttftMs = ttftMs, durationMs = 10, httpStatus = httpStatus, streamed = streamed, outcome = outcome, errorType = errorType,
	)

	private fun get(uri: String) = client.get().uri(uri).asAdmin().exchange()

	@Test
	fun `day buckets follow the requested time zone while the totals do not change`() {
		get("/admin/v1/usage/summary?$window&groupBy=day")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.tz").isEqualTo("UTC")
			.jsonPath("$.groupBy").isEqualTo("day")
			.jsonPath("$.groups.length()").isEqualTo(1)
			.jsonPath("$.groups[0].key").isEqualTo("2025-03-01")
			.jsonPath("$.groups[0].requests").isEqualTo(8)
			.jsonPath("$.totals.requests").isEqualTo(8)
			.jsonPath("$.totals.okRequests").isEqualTo(7)
			.jsonPath("$.totals.inputTokens").isEqualTo(1180)
			.jsonPath("$.totals.cachedInputTokens").isEqualTo(160)
			.jsonPath("$.totals.outputTokens").isEqualTo(118)
			.jsonPath("$.totals.cost").isEqualTo(0.00742000)
			.jsonPath("$.totals.priceMissingRequests").isEqualTo(1)
			.jsonPath("$.totals.usageUnknownRequests").isEqualTo(1)

		get("/admin/v1/usage/summary?$window&groupBy=day&tz=Asia/Seoul")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.tz").isEqualTo("Asia/Seoul")
			.jsonPath("$.groups.length()").isEqualTo(2)
			.jsonPath("$.groups[0].key").isEqualTo("2025-03-01")
			.jsonPath("$.groups[0].requests").isEqualTo(3)
			.jsonPath("$.groups[0].inputTokens").isEqualTo(350)
			.jsonPath("$.groups[0].cost").isEqualTo(0.00035000)
			.jsonPath("$.groups[1].key").isEqualTo("2025-03-02")
			.jsonPath("$.groups[1].requests").isEqualTo(5)
			.jsonPath("$.groups[1].okRequests").isEqualTo(4)
			.jsonPath("$.groups[1].inputTokens").isEqualTo(830)
			.jsonPath("$.groups[1].cachedInputTokens").isEqualTo(110)
			.jsonPath("$.groups[1].outputTokens").isEqualTo(83)
			.jsonPath("$.groups[1].cost").isEqualTo(0.00707000)
			.jsonPath("$.groups[1].priceMissingRequests").isEqualTo(1)
			.jsonPath("$.groups[1].usageUnknownRequests").isEqualTo(1)
			.jsonPath("$.totals.requests").isEqualTo(8)
			.jsonPath("$.totals.cost").isEqualTo(0.00742000)
	}

	@Test
	fun `project buckets carry the project name and sort by cost`() {
		get("/admin/v1/usage/summary?$window&groupBy=project")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.groups.length()").isEqualTo(2)
			.jsonPath("$.groups[0].key").isEqualTo(projectA.toString())
			.jsonPath("$.groups[0].label").isEqualTo(projectAName)
			.jsonPath("$.groups[0].requests").isEqualTo(4)
			.jsonPath("$.groups[0].inputTokens").isEqualTo(1000)
			.jsonPath("$.groups[0].cachedInputTokens").isEqualTo(150)
			.jsonPath("$.groups[0].outputTokens").isEqualTo(100)
			.jsonPath("$.groups[0].cost").isEqualTo(0.00730000)
			.jsonPath("$.groups[1].key").isEqualTo(projectB.toString())
			.jsonPath("$.groups[1].requests").isEqualTo(4)
			.jsonPath("$.groups[1].okRequests").isEqualTo(3)
			.jsonPath("$.groups[1].cost").isEqualTo(0.00012000)
			.jsonPath("$.groups[1].priceMissingRequests").isEqualTo(1)
			.jsonPath("$.groups[1].usageUnknownRequests").isEqualTo(1)
	}

	@Test
	fun `model buckets name the provider and use the served model`() {
		get("/admin/v1/usage/summary?$window&groupBy=model")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.groups.length()").isEqualTo(3)
			.jsonPath("$.groups[0].key").isEqualTo("fake-large")
			.jsonPath("$.groups[0].provider").isEqualTo("fake")
			.jsonPath("$.groups[0].requests").isEqualTo(2)
			.jsonPath("$.groups[0].cost").isEqualTo(0.00700000)
			.jsonPath("$.groups[1].key").isEqualTo("fake-small")
			.jsonPath("$.groups[1].requests").isEqualTo(5)
			.jsonPath("$.groups[1].okRequests").isEqualTo(4)
			.jsonPath("$.groups[1].inputTokens").isEqualTo(420)
			.jsonPath("$.groups[1].cost").isEqualTo(0.00042000)
			.jsonPath("$.groups[1].usageUnknownRequests").isEqualTo(1)
			.jsonPath("$.groups[2].key").isEqualTo("gpt-x")
			.jsonPath("$.groups[2].provider").isEqualTo("openai")
			.jsonPath("$.groups[2].cost").isEqualTo(0)
			.jsonPath("$.groups[2].priceMissingRequests").isEqualTo(1)
	}

	@Test
	fun `tag buckets include records without a tag`() {
		get("/admin/v1/usage/summary?$window&groupBy=tag")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.groups.length()").isEqualTo(3)
			.jsonPath("$.groups[0].key").isEmpty
			.jsonPath("$.groups[0].requests").isEqualTo(2)
			.jsonPath("$.groups[0].cost").isEqualTo(0.00400000)
			.jsonPath("$.groups[1].key").isEqualTo("beta")
			.jsonPath("$.groups[1].requests").isEqualTo(3)
			.jsonPath("$.groups[1].inputTokens").isEqualTo(430)
			.jsonPath("$.groups[1].cost").isEqualTo(0.00307000)
			.jsonPath("$.groups[2].key").isEqualTo("alpha")
			.jsonPath("$.groups[2].requests").isEqualTo(3)
			.jsonPath("$.groups[2].cost").isEqualTo(0.00035000)
	}

	@Test
	fun `filters narrow the summary`() {
		get("/admin/v1/usage/summary?$window&groupBy=day&projectId=$projectA&tag=alpha")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.totals.requests").isEqualTo(2)
			.jsonPath("$.totals.inputTokens").isEqualTo(300)
			.jsonPath("$.totals.cost").isEqualTo(0.00030000)
		get("/admin/v1/usage/summary?$window&groupBy=day&model=fake-large")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.totals.requests").isEqualTo(2)
		get("/admin/v1/usage/summary?$window&groupBy=day&provider=openai")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.totals.requests").isEqualTo(1)
			.jsonPath("$.totals.cost").isEqualTo(0)
		get("/admin/v1/usage/summary?from=2025-03-05&to=2025-03-06&groupBy=day")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.groups.length()").isEqualTo(0)
			.jsonPath("$.totals.requests").isEqualTo(0)
			.jsonPath("$.totals.cost").isEqualTo(0)
	}

	@Test
	fun `records come newest first with project name and key prefix`() {
		get("/admin/v1/usage/records?$window&projectId=$projectA")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.records.length()").isEqualTo(4)
			.jsonPath("$.nextBefore").isEmpty
			.jsonPath("$.records[0].id").isEqualTo(ids[3])
			.jsonPath("$.records[0].projectName").isEqualTo(projectAName)
			.jsonPath("$.records[0].keyPrefix").isEqualTo(keyPrefixA)
			.jsonPath("$.records[0].modelServed").isEqualTo("fake-large")
			.jsonPath("$.records[0].tag").isEmpty
			.jsonPath("$.records[0].cost").isEqualTo(0.00400000)
			.jsonPath("$.records[0].startedAt").isEqualTo("2025-03-01T15:30:00Z")
			.jsonPath("$.records[0].usageSource").isEqualTo("response")
			.jsonPath("$.records[0].outcome").isEqualTo("ok")
			.jsonPath("$.records[2].streamed").isEqualTo(true)
			.jsonPath("$.records[2].ttftMs").isEqualTo(12)
			.jsonPath("$.records[3].id").isEqualTo(ids[0])
		get("/admin/v1/usage/records?$window&outcome=upstream_error")
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.records.length()").isEqualTo(1)
			.jsonPath("$.records[0].errorType").isEqualTo("rate_limit_error")
			.jsonPath("$.records[0].httpStatus").isEqualTo(429)
			.jsonPath("$.records[0].cost").isEmpty
			.jsonPath("$.records[0].usageSource").isEqualTo("none")
	}

	@Test
	fun `the cursor walks every record exactly once`() {
		val seen = mutableListOf<Long>()
		var before: Long? = null
		var pages = 0
		do {
			val uri = "/admin/v1/usage/records?$window&limit=3" + (before?.let { "&before=$it" } ?: "")
			val page = get(uri).expectStatus().isOk.expectBody(UsageRecordsResponse::class.java).returnResult().responseBody!!
			seen += page.records.map { it.id }
			before = page.nextBefore
			pages++
		} while (before != null)
		assertEquals(3, pages)
		assertEquals(ids.sortedDescending(), seen)
		assertEquals(seen.toSet().size, seen.size)
	}

	@Test
	fun `bad parameters are 400 in the admin error shape`() {
		val cases = listOf(
			"/admin/v1/usage/summary?$window&tz=Mars/Olympus" to "tz must be a valid time zone id such as Asia/Seoul",
			"/admin/v1/usage/summary?from=2025-03-03&to=2025-03-01" to "from must be before to",
			"/admin/v1/usage/summary?$window&groupBy=hour" to "groupBy must be one of day, project, model, tag",
			"/admin/v1/usage/summary?from=yesterday" to "from must be a date (YYYY-MM-DD) or an ISO-8601 instant",
			"/admin/v1/usage/summary?$window&projectId=nope" to "projectId must be a UUID",
			"/admin/v1/usage/records?$window&limit=0" to "limit must be between 1 and 200",
			"/admin/v1/usage/records?$window&limit=201" to "limit must be between 1 and 200",
			"/admin/v1/usage/records?$window&outcome=exploded" to "outcome must be one of ok, upstream_error, client_abort, timeout, gateway_error",
		)
		for ((uri, message) in cases) {
			get(uri)
				.expectStatus().isBadRequest
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("bad_request")
				.jsonPath("$.error.message").isEqualTo(message)
		}
		get("/admin/v1/usage/records?$window&limit=abc").expectStatus().isBadRequest
	}

	@Test
	fun `the usage views need the admin token`() {
		client.get().uri("/admin/v1/usage/summary").exchange().expectStatus().isUnauthorized
		client.get().uri("/admin/v1/usage/records").exchange().expectStatus().isUnauthorized
	}

	@Test
	fun `defaults cover the last seven days`() {
		val body = get("/admin/v1/usage/summary?projectId=$projectA")
			.expectStatus().isOk
			.expectBody(UsageSummaryResponse::class.java)
			.returnResult().responseBody!!
		assertEquals("UTC", body.tz)
		assertEquals("day", body.groupBy)
		assertTrue(body.to.isAfter(body.from))
		assertEquals(7L * 24 * 3600, body.to.epochSecond - body.from.epochSecond)
		assertNull(body.groups.firstOrNull { it.key == "2025-03-01" }, "March records must fall outside the default window")
	}
}
