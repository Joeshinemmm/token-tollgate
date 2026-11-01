package tokentollgate.db

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import tokentollgate.IntegrationTest
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@IntegrationTest
class RepositoryTest {

	@Autowired lateinit var projects: ProjectRepository
	@Autowired lateinit var keys: VirtualKeyRepository
	@Autowired lateinit var prices: ModelPriceRepository
	@Autowired lateinit var usage: UsageRecordRepository
	@Autowired lateinit var db: DatabaseClient

	private val now: Instant = dbNow()

	private suspend fun newProject() = projects.save(Project(name = "project-${UUID.randomUUID()}"))

	private suspend fun newKey(projectId: UUID) = keys.save(
		VirtualKey(projectId = projectId, name = "default", keyPrefix = "tt_abcd", keyHash = "hash-${UUID.randomUUID()}"),
	)

	@Test
	fun `project, key, price and usage record round-trip through the database`() = runBlocking {
		val project = newProject()
		val projectId = assertNotNull(project.id, "database should generate the project id")

		val key = newKey(projectId)
		val keyId = assertNotNull(key.id)
		assertEquals(key, keys.findByKeyHash(key.keyHash))
		assertNull(keys.findByKeyHash("missing"))

		val price = prices.save(
			ModelPrice(
				provider = "fake",
				model = "fake-small",
				inputPerMtok = BigDecimal("0.15000000"),
				outputPerMtok = BigDecimal("0.60000000"),
				effectiveFrom = now,
			),
		)
		val priceId = assertNotNull(price.id)

		val saved = usage.save(
			UsageRecord(
				requestId = UUID.randomUUID(),
				startedAt = now,
				keyId = keyId,
				projectId = projectId,
				tag = "unit-test",
				protocol = Protocol.ANTHROPIC,
				provider = "fake",
				modelRequested = "fake-small",
				modelServed = "fake-small",
				inputTokens = 120,
				outputTokens = 40,
				cost = BigDecimal("0.00004200"),
				priceId = priceId,
				usageSource = UsageSource.STREAM,
				ttftMs = 35,
				durationMs = 410,
				httpStatus = 200,
				streamed = true,
				outcome = Outcome.OK,
			),
		)
		val id = assertNotNull(saved.id)

		val loaded = assertNotNull(usage.findById(id))
		assertEquals(Protocol.ANTHROPIC, loaded.protocol)
		assertEquals(UsageSource.STREAM, loaded.usageSource)
		assertEquals(Outcome.OK, loaded.outcome)
		assertEquals(BigDecimal("0.00004200"), loaded.cost)
		assertEquals(now, loaded.startedAt)

		val stored = db.sql("SELECT protocol, usage_source, outcome FROM usage_records WHERE id = :id")
			.bind("id", id)
			.map { row -> listOf(row.get("protocol"), row.get("usage_source"), row.get("outcome")) }
			.one()
			.awaitFirstOrNull()
		assertEquals(listOf("anthropic", "stream", "ok"), stored)
	}

	@Test
	fun `duplicate key hash is rejected`() = runBlocking {
		val projectId = assertNotNull(newProject().id)
		val first = newKey(projectId)
		assertFailsWith<DataIntegrityViolationException> {
			keys.save(first.copy(id = null, name = "duplicate"))
		}
		Unit
	}

	@Test
	fun `check constraint rejects an unknown outcome`() = runBlocking {
		val projectId = assertNotNull(newProject().id)
		val keyId = assertNotNull(newKey(projectId).id)
		assertFailsWith<DataIntegrityViolationException> {
			db.sql(
				"""
				INSERT INTO usage_records
					(request_id, started_at, key_id, project_id, protocol, provider, model_requested,
					 usage_source, duration_ms, http_status, streamed, outcome)
				VALUES
					(:requestId, now(), :keyId, :projectId, 'openai', 'fake', 'fake-small',
					 'response', 1, 200, false, 'bogus')
				""".trimIndent(),
			)
				.bind("requestId", UUID.randomUUID())
				.bind("keyId", keyId)
				.bind("projectId", projectId)
				.fetch()
				.rowsUpdated()
				.awaitFirstOrNull()
		}
		Unit
	}
}
