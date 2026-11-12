package tokentollgate.relay.anthropic

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
import tokentollgate.createProject
import tokentollgate.db.ModelPriceRepository
import tokentollgate.db.Outcome
import tokentollgate.db.Protocol
import tokentollgate.db.UsageRecordRepository
import tokentollgate.db.UsageSource
import tokentollgate.ensureFakePrices
import tokentollgate.issueKey
import tokentollgate.json
import tokentollgate.messagesBody
import tokentollgate.readSse
import tokentollgate.relay.HeaderRules
import tokentollgate.requestIdOf
import tokentollgate.sseEvent
import tokentollgate.withKey
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class AnthropicRelayTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var records: UsageRecordRepository
	@Autowired lateinit var prices: ModelPriceRepository
	@Autowired lateinit var mapper: ObjectMapper
	@LocalServerPort var port: Int = 0

	private lateinit var key: String

	@BeforeEach
	fun setUp() = runBlocking {
		prices.ensureFakePrices()
		if (!::key.isInitialized) key = client.issueKey(client.createProject().id).key
	}

	private val path = AnthropicMessagesController.PATH

	private fun post(body: Any) = client.post().uri(path).withKey(key).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

	private val fullStream = listOf(
		"message_start", "content_block_start", "ping",
		"content_block_delta", "content_block_delta", "content_block_delta", "content_block_delta",
		"content_block_delta", "content_block_delta", "content_block_delta", "content_block_delta",
		"content_block_stop", "message_delta", "message_stop",
	)

	@Test
	fun `plain response is relayed unchanged and recorded with normalized usage`() = runBlocking {
		val body = post(messagesBody("fake-relay:in=120,cached=20,out=8"))
			.expectStatus().isOk
			.expectHeader().exists("request-id")
			.expectHeader().exists(HeaderRules.REQUEST_ID_HEADER)
			.expectBody()
			.jsonPath("$.type").isEqualTo("message")
			.jsonPath("$.model").isEqualTo("fake-relay")
			.jsonPath("$.content[0].text").isEqualTo(FakeText.content(8))
			.jsonPath("$.usage.input_tokens").isEqualTo(100)
			.jsonPath("$.usage.cache_read_input_tokens").isEqualTo(20)
			.jsonPath("$.usage.output_tokens").isEqualTo(8)
		val record = records.awaitRecord(requestIdOf(body))

		assertEquals(Protocol.ANTHROPIC, record.protocol)
		assertEquals("fake", record.provider)
		assertEquals("fake-relay", record.modelServed)
		assertEquals(120, record.inputTokens)
		assertEquals(20, record.cachedInputTokens)
		assertEquals(8, record.outputTokens)
		assertEquals(0, BigDecimal("0.00011800").compareTo(record.cost), "cost was ${record.cost}")
		assertEquals(UsageSource.RESPONSE, record.usageSource)
		assertEquals(Outcome.OK, record.outcome)
		assertFalse(record.streamed)
		assertNull(record.ttftMs)
	}

	@Test
	fun `a stream passes through byte for byte and is recorded from message_start and message_delta`() = runBlocking {
		val body = messagesBody("fake-relay:in=120,cached=20,out=8,ttft=100", stream = true)
		val relayed = readSse(port, path, key, body)
		val direct = readSse(port, "/fake$path", FakeUpstreamInitializer.FAKE_KEY, body)

		assertEquals(200, relayed.status?.value())
		assertNull(relayed.error)
		assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(relayed.headers?.contentType), "content type ${relayed.headers?.contentType}")
		assertEquals(fullStream, relayed.frames.map { it.sseEvent() })
		val ids = Regex("msg_[A-Za-z0-9]+")
		assertEquals(direct.raw.replace(ids, "msg_X"), relayed.raw.replace(ids, "msg_X"), "relayed bytes differ from the upstream stream")

		val record = records.awaitRecord(relayed.requestId)
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
	fun `an upstream 529 is passed through and recorded`() = runBlocking {
		val body = post(messagesBody("fake-relay:err=529"))
			.expectStatus().isEqualTo(529)
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("overloaded_error")
			.jsonPath("$.request_id").exists()
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("overloaded_error", record.errorType)
		assertEquals(529, record.httpStatus)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertNull(record.cost)
	}

	@Test
	fun `an upstream stream cut mid-way ends with a gateway error event`() = runBlocking {
		val result = readSse(port, path, key, messagesBody("fake-relay:out=5,abort=2", stream = true))

		assertEquals(200, result.status?.value())
		assertNull(result.error)
		assertEquals(listOf("message_start", "content_block_start", "ping", "content_block_delta", "content_block_delta", "error"), result.frames.map { it.sseEvent() })
		val error = mapper.json(result.frames.last())
		assertEquals("error", error.path("type").stringValue())
		assertEquals("api_error", error.path("error").path("type").stringValue())
		assertTrue(error.path("error").path("message").stringValue().contains("stream_interrupted"))
		assertFalse(result.raw.contains("message_stop"))

		val record = records.awaitRecord(result.requestId)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("stream_interrupted", record.errorType)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertEquals(0, record.inputTokens)
	}

	@Test
	fun `an upstream error event is forwarded and recorded`() = runBlocking {
		val result = readSse(port, path, key, messagesBody("fake-relay:out=5,streamerr=2", stream = true))

		assertNull(result.error)
		assertEquals("error", result.frames.last().sseEvent())
		assertEquals("overloaded_error", mapper.json(result.frames.last()).path("error").path("type").stringValue())
		assertEquals(6, result.frames.size)

		val record = records.awaitRecord(result.requestId)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("overloaded_error", record.errorType)
	}

	@Test
	fun `a slow upstream is a 504 in Anthropic shape and a timeout record`() = runBlocking {
		val body = post(messagesBody("slow-model:ttft=2000,out=1"))
			.expectStatus().isEqualTo(504)
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("timeout_error")
			.jsonPath("$.error.message").value<String> { assertTrue(it.contains("upstream_timeout")) }
			.jsonPath("$.request_id").exists()
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(Outcome.TIMEOUT, record.outcome)
		assertEquals("upstream_timeout", record.errorType)
		assertEquals("fake-slow", record.provider)
	}

	@Test
	fun `a model routed to a provider without its key is a 503 in Anthropic shape`() = runBlocking {
		val body = post(messagesBody("claude-x"))
			.expectStatus().isEqualTo(503)
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("api_error")
			.jsonPath("$.error.message").value<String> { assertTrue(it.contains("provider_disabled")) }
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals("anthropic", record.provider)
		assertEquals(Outcome.GATEWAY_ERROR, record.outcome)
		assertEquals("provider_disabled", record.errorType)
	}

	@Test
	fun `the virtual key never reaches the upstream`() = runBlocking {
		val body = post(messagesBody("nokey-model"))
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("authentication_error")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals("fake-nokey", record.provider)
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("authentication_error", record.errorType)
	}

	@Test
	fun `a client that disconnects mid-stream is recorded as client_abort`() = runBlocking {
		val result = readSse(port, path, key, messagesBody("fake-relay:out=30,gap=200", stream = true), takeFrames = 4)

		assertEquals(200, result.status?.value())
		val record = records.awaitRecord(result.requestId, timeoutMs = 10_000)
		assertEquals(Outcome.CLIENT_ABORT, record.outcome)
		assertEquals(UsageSource.NONE, record.usageSource)
		assertTrue(record.streamed)
	}

	@Test
	fun `a request without a model is refused in Anthropic shape and not recorded`() = runBlocking {
		val body = post(mapOf("max_tokens" to 64, "messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.message").isEqualTo("model: Field required")
			.jsonPath("$.request_id").exists()
		val requestId = requestIdOf(body)
		delay(500)
		assertNull(records.findByRequestId(requestId))
	}

	@Test
	fun `upstream validation such as a missing max_tokens is passed through`() = runBlocking {
		val body = post(messagesBody("fake-relay:out=3", maxTokens = null))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.message").isEqualTo("max_tokens: Field required")
		val record = records.awaitRecord(requestIdOf(body))
		assertEquals(Outcome.UPSTREAM_ERROR, record.outcome)
		assertEquals("invalid_request_error", record.errorType)
		assertEquals(400, record.httpStatus)
	}
}
