package tokentollgate.admin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.asAdmin
import tokentollgate.createProject
import tokentollgate.db.Outcome
import tokentollgate.db.Protocol
import tokentollgate.db.UsageRecord
import tokentollgate.db.UsageRecordRepository
import tokentollgate.db.UsageSource
import tokentollgate.db.dbNow
import tokentollgate.issueKey
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@IntegrationTest
class PriceAdminApiTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var usage: UsageRecordRepository

	private fun put(body: Map<String, Any?>) =
		client.put().uri("/admin/v1/prices").asAdmin()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()

	private fun price(provider: String, model: String = "m", input: String = "2.00", effectiveFrom: String = "2026-11-01") = mapOf(
		"provider" to provider,
		"model" to model,
		"inputPerMtok" to BigDecimal(input),
		"outputPerMtok" to BigDecimal("10.00"),
		"cachedInputPerMtok" to BigDecimal("0.10"),
		"effectiveFrom" to effectiveFrom,
	)

	@Test
	fun `put creates then updates, get lists, delete removes`() {
		val provider = "api-${UUID.randomUUID()}"

		val created = put(price(provider))
			.expectStatus().isCreated
			.expectBody(PriceResponse::class.java)
			.returnResult().responseBody!!
		assertEquals(BigDecimal("2.00000000"), created.inputPerMtok)
		assertEquals("USD", created.currency)
		assertEquals("2026-11-01T00:00:00Z", created.effectiveFrom.toString())

		val updated = put(price(provider, input = "3.00"))
			.expectStatus().isOk
			.expectBody(PriceResponse::class.java)
			.returnResult().responseBody!!
		assertEquals(created.id, updated.id)
		assertEquals(BigDecimal("3.00000000"), updated.inputPerMtok)

		put(price(provider, model = "other"))
			.expectStatus().isCreated

		val all = client.get().uri("/admin/v1/prices?provider=$provider").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBodyList(PriceResponse::class.java)
			.returnResult().responseBody!!
		assertEquals(listOf("m", "other"), all.map { it.model })

		client.get().uri("/admin/v1/prices?provider=$provider&model=other").asAdmin()
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1)
			.jsonPath("$[0].model").isEqualTo("other")

		client.delete().uri("/admin/v1/prices/${created.id}").asAdmin()
			.exchange()
			.expectStatus().isNoContent
		client.delete().uri("/admin/v1/prices/${created.id}").asAdmin()
			.exchange()
			.expectStatus().isNotFound
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("not_found")
	}

	@Test
	fun `invalid input is rejected with 400 and a field message`() {
		val provider = "api-${UUID.randomUUID()}"
		fun rejects(body: Map<String, Any?>, expected: String) {
			put(body)
				.expectStatus().isBadRequest
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("bad_request")
				.jsonPath("$.error.message").value<String> { assertTrue(it.contains(expected), it) }
		}
		rejects(price(provider, input = "-1"), "inputPerMtok must not be negative")
		rejects(price(provider, model = " "), "model must not be blank")
		rejects(price(provider) + ("currency" to "EUR"), "currency must be USD")
		rejects(price(provider, effectiveFrom = "01/11/2026"), "effectiveFrom must be a date")
		rejects(price(provider) - "outputPerMtok", "outputPerMtok is required")
		rejects(price(provider, input = "0.000000001"), "inputPerMtok must have at most 8 decimal places")
	}

	@Test
	fun `rates are written as plain decimals, not scientific notation`() {
		val provider = "api-${UUID.randomUUID()}"
		val body = put(price(provider, input = "0.00000015"))
			.expectStatus().isCreated
			.expectBody(String::class.java)
			.returnResult().responseBody!!
		assertTrue(body.contains("\"inputPerMtok\":0.00000015"), body)
		assertFalse(body.contains("E-"), body)
	}

	@Test
	fun `a price referenced by a usage record cannot be deleted`() = runBlocking<Unit> {
		val provider = "api-${UUID.randomUUID()}"
		val created = put(price(provider))
			.expectStatus().isCreated
			.expectBody(PriceResponse::class.java)
			.returnResult().responseBody!!

		val project = client.createProject()
		val key = client.issueKey(project.id)
		usage.save(
			UsageRecord(
				requestId = UUID.randomUUID(),
				startedAt = dbNow(),
				keyId = key.id,
				projectId = project.id,
				protocol = Protocol.OPENAI,
				provider = provider,
				modelRequested = "m",
				inputTokens = 10,
				outputTokens = 5,
				cost = BigDecimal("0.00007000"),
				priceId = created.id,
				usageSource = UsageSource.RESPONSE,
				durationMs = 12,
				httpStatus = 200,
				streamed = false,
				outcome = Outcome.OK,
			),
		)

		client.delete().uri("/admin/v1/prices/${created.id}").asAdmin()
			.exchange()
			.expectStatus().isEqualTo(409)
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("conflict")
			.jsonPath("$.error.message").value<String> { assertTrue(it.contains("referenced by usage records"), it) }
	}
}
