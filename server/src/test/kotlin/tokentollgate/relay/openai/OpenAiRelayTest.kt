package tokentollgate.relay.openai

import fakeprovider.text.FakeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.FakeUpstreamInitializer
import tokentollgate.IntegrationTest
import tokentollgate.awaitRecord
import tokentollgate.chatBody
import tokentollgate.createProject
import tokentollgate.db.ModelPriceRepository
import tokentollgate.db.Outcome
import tokentollgate.db.Protocol
import tokentollgate.db.UsageRecordRepository
import tokentollgate.db.UsageSource
import tokentollgate.ensureFakePrices
import tokentollgate.issueKey
import tokentollgate.json
import tokentollgate.readSse
import tokentollgate.relay.HeaderRules
import tokentollgate.requestIdOf
import tokentollgate.withKey
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class OpenAiRelayTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var records: UsageRecordRepository
	@Autowired lateinit var prices: ModelPriceRepository
	@Autowired lateinit var mapper: ObjectMapper
	@LocalServerPort var port: Int = 0

	private lateinit var key: String
	private lateinit var projectId: UUID

	@BeforeEach
	fun setUp() = runBlocking {
		prices.ensureFakePrices()
		if (!::key.isInitialized) {
			val project = client.createProject()
			projectId = project.id
			key = client.issueKey(project.id).key
		}
	}

	private fun post(body: Any, tag: String? = null) = client.post().uri(OpenAiChatController.PATH).withKey(key)
		.also { if (tag != null) it.header("X-Tollgate-Tag", tag) }
		.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

	@Test
	fun `plain response is relayed unchanged and recorded with cost`() = runBlocking {
		val body = post(chatBody("fake-relay:in=120,cached=20,out=8"), tag = "relay-test")
			.expectStatus().isOk
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectHeader().exists(HeaderRules.REQUEST_ID_HEADER)
			.expectBody()
			.jsonPath("$.object").isEqualTo("chat.completion")
			.jsonPath("$.model").isEqualTo("fake-relay")
			.jsonPath("$.choices[0].message.content").isEqualTo(FakeText.content(8))
			.jsonPath("$.usage.prompt_tokens").isEqualTo(120)
			.jsonPath("$.usage.prompt_tokens_details.cached_tokens").isEqualTo(20)
		val record = records.awaitRecord(requestIdOf(body))

		assertEquals(projectId, record.projectId)
		assertEquals("relay-test", record.tag)
		assertEquals(Protocol.OPENAI, record.protocol)
		assertEquals("fake", record.provider)
		assertEquals("fake-relay:in=120,cached=20,out=8", record.modelRequested)
		assertEquals("fake-relay", record.modelServed)
		assertEquals(120, record.inputTokens)
		assertEquals(20, record.cachedInputTokens)
		assertEquals(8, record.outputTokens)
		assertEquals(0, BigDecimal("0.00011800").compareTo(record.cost), "cost was ${record.cost}")
		assertNotNull(record.priceId)
		assertEquals(UsageSource.RESPONSE, record.usageSource)
		assertEquals(Outcome.OK, record.outcome)
		assertEquals(200, record.httpStatus)
		assertFalse(record.streamed)
		assertNull(record.ttftMs)
		assertNull(record.errorType)
		assertTrue(record.durationMs >= 0)
	}

	@Test
	fun `include_usage on a plain request changes nothing`() = runBlocking {
		val body = post(chatBody("fake-relay:in=120,cached=20,out=8", includeUsage = true))
			.expectStatus().isOk
			.expectBody().jsonPath("$.usage.completion_tokens").isEqualTo(8)
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(UsageSource.RESPONSE, record.usageSource)
		assertEquals(0, BigDecimal("0.00011800").compareTo(record.cost))
	}

	@Test
	fun `stream without include_usage hides the usage chunk but records it`() = runBlocking {
		val result = readSse(port, OpenAiChatController.PATH, key, chatBody("fake-relay:in=120,cached=20,out=8,ttft=100", stream = true))

		assertEquals(200, result.status?.value())
		assertNull(result.error)
		assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(result.headers?.contentType), "content type ${result.headers?.contentType}")
		val frames = result.frames
		assertEquals(11, frames.size, frames.toString())
		assertEquals("data: [DONE]", frames.last())
		frames.dropLast(1).map { mapper.json(it) }.forEach { chunk ->
			assertTrue(chunk.path("choices").size() > 0, "usage chunk must not reach the client: $chunk")
		}
		assertEquals(FakeText.chunk(0), mapper.json(frames[1]).path("choices").path(0).path("delta").path("content").stringValue())

		val record = records.awaitRecord(result.requestId)
		assertEquals(UsageSource.STREAM, record.usageSource)
		assertEquals(120, record.inputTokens)
		assertEquals(20, record.cachedInputTokens)
		assertEquals(8, record.outputTokens)
		assertEquals(0, BigDecimal("0.00011800").compareTo(record.cost))
		assertEquals("fake-relay", record.modelServed)
		assertTrue(record.streamed)
		assertEquals(Outcome.OK, record.outcome)
		assertNotNull(record.ttftMs)
		assertTrue(record.ttftMs >= 100, "ttft was ${record.ttftMs}")
	}

	@Test
	fun `stream with include_usage forwards the usage chunk`() = runBlocking {
		val result = readSse(port, OpenAiChatController.PATH, key, chatBody("fake-relay:in=50,out=3", stream = true, includeUsage = true))

		assertNull(result.error)
		val frames = result.frames
		assertEquals(7, frames.size, frames.toString())
		val usageChunk = mapper.json(frames[5])
		assertEquals(0, usageChunk.path("choices").size())
		assertEquals(50, usageChunk.path("usage").path("prompt_tokens").intValue())
		assertEquals("data: [DONE]", frames.last())

		val record = records.awaitRecord(result.requestId)
		assertEquals(UsageSource.STREAM, record.usageSource)
		assertEquals(3, record.outputTokens)
		assertEquals(Outcome.OK, record.outcome)
	}

	@Test
	fun `an upstream error status and body are passed through and recorded`() = runBlocking {
		val body = post(chatBody("fake-relay:err=429"))
			.expectStatus().isEqualTo(429)
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("rate_limit_error")
			.jsonPath("$.error.message").isEqualTo("Rate limit reached for requests")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("rate_limit_error", record.errorType)
		assertEquals(429, record.httpStatus)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertNull(record.cost)
		assertEquals(0, record.inputTokens)
	}

	@Test
	fun `an upstream stream cut mid-way ends with a gateway error frame`() = runBlocking {
		val result = readSse(port, OpenAiChatController.PATH, key, chatBody("fake-relay:out=5,abort=2", stream = true))

		assertEquals(200, result.status?.value())
		assertNull(result.error, "the gateway must end the stream cleanly, got ${result.error}")
		val frames = result.frames
		assertEquals(4, frames.size, frames.toString())
		assertEquals(FakeText.chunk(1), mapper.json(frames[2]).path("choices").path(0).path("delta").path("content").stringValue())
		val error = mapper.json(frames[3]).path("error")
		assertEquals("stream_interrupted", error.path("code").stringValue())
		assertFalse(result.raw.contains("[DONE]"))

		val record = records.awaitRecord(result.requestId)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("stream_interrupted", record.errorType)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertTrue(record.streamed)
	}

	@Test
	fun `an upstream error frame is forwarded and recorded`() = runBlocking {
		val result = readSse(port, OpenAiChatController.PATH, key, chatBody("fake-relay:out=5,streamerr=2", stream = true))

		assertNull(result.error)
		val frames = result.frames
		assertEquals(4, frames.size, frames.toString())
		assertEquals("server_error", mapper.json(frames[3]).path("error").path("type").stringValue())
		assertFalse(result.raw.contains("[DONE]"))

		val record = records.awaitRecord(result.requestId)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("server_error", record.errorType)
	}

	@Test
	fun `a slow upstream is a 504 and a timeout record`() = runBlocking {
		val body = post(chatBody("slow-model:ttft=2000,out=1"))
			.expectStatus().isEqualTo(504)
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("upstream_timeout")
			.jsonPath("$.error.type").isEqualTo("server_error")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(Outcome.TIMEOUT, record.outcome)
		assertEquals("upstream_timeout", record.errorType)
		assertEquals("fake-slow", record.provider)
		assertEquals(504, record.httpStatus)
	}

	@Test
	fun `an unknown model is a 404 recorded without a provider`() = runBlocking {
		val body = post(chatBody("unknown-model"))
			.expectStatus().isNotFound
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("model_not_found")
			.jsonPath("$.error.param").isEqualTo("model")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals("none", record.provider)
		assertEquals(Outcome.GATEWAY_ERROR, record.outcome)
		assertEquals("model_not_found", record.errorType)
		assertEquals(404, record.httpStatus)
	}

	@Test
	fun `a provider without its key is a 503 naming the variable`() = runBlocking {
		val body = post(chatBody("gpt-6-astra"))
			.expectStatus().isEqualTo(503)
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("provider_disabled")
			.jsonPath("$.error.message").value<String> { assertTrue(it.contains(FakeUpstreamInitializer.NEVER_SET)) }
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals("openai", record.provider)
		assertEquals(Outcome.GATEWAY_ERROR, record.outcome)
		assertEquals("provider_disabled", record.errorType)
	}

	@Test
	fun `the virtual key never reaches the upstream`() = runBlocking {
		val body = post(chatBody("nokey-model"))
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("authentication_error")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals("fake-nokey", record.provider)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("authentication_error", record.errorType)
		assertEquals(401, record.httpStatus)
	}

	@Test
	fun `a client that disconnects mid-stream is recorded as client_abort`() = runBlocking {
		val result = readSse(port, OpenAiChatController.PATH, key, chatBody("fake-relay:out=30,gap=200", stream = true), takeFrames = 2)

		assertEquals(200, result.status?.value())
		val record = records.awaitRecord(result.requestId, timeoutMs = 10_000)
		assertEquals(Outcome.CLIENT_ABORT, record.outcome)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertTrue(record.streamed)
	}

	@Test
	fun `a request without a model is refused before anything is recorded`() = runBlocking {
		val body = post(mapOf("messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.param").isEqualTo("model")
		val requestId = requestIdOf(body)
		delay(500)
		assertNull(records.findByRequestId(requestId))
	}
}
