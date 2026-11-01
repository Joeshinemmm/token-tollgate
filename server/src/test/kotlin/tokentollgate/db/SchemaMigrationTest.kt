package tokentollgate.db

import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import tokentollgate.IntegrationTest
import kotlin.test.assertEquals

@IntegrationTest
class SchemaMigrationTest {

	@Autowired
	lateinit var db: DatabaseClient

	@Test
	fun `flyway applied exactly one successful migration`() = runBlocking {
		val applied = db.sql("SELECT count(*) AS n FROM flyway_schema_history WHERE success")
			.map { row -> (row.get("n") as Number).toLong() }
			.one()
			.awaitSingle()
		assertEquals(1L, applied)
	}

	@Test
	fun `all four tables exist`() = runBlocking {
		val tables = db.sql(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
		)
			.map { row -> row.get("table_name") as String }
			.all()
			.collectList()
			.awaitFirst()
		assertEquals(
			listOf("flyway_schema_history", "model_prices", "projects", "usage_records", "virtual_keys"),
			tables,
		)
	}
}
