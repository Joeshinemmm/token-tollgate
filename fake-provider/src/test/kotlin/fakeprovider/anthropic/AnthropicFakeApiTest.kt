package fakeprovider.anthropic

import fakeprovider.anthropicBody
import fakeprovider.error.FakeErrorHandler.Companion.REQUEST_ID_HEADER
import fakeprovider.json
import fakeprovider.readSse
import fakeprovider.sseEvent
import fakeprovider.text.FakeText
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AnthropicFakeApiTest {

	@Autowired
	lateinit var client: WebTestClient

	@Autowired
	lateinit var mapper: ObjectMapper

	@LocalServerPort
	var port: Int = 0

	private val path = AnthropicFakeController.PATH

	private fun post(body: Any) = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

	@Test
	fun `message reports usage with cache reads outside input_tokens`() {
		post(anthropicBody("fake-small:in=120,cached=20,out=8"))
			.expectStatus().isOk
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectHeader().value(REQUEST_ID_HEADER) { assertTrue(it.startsWith("req_")) }
			.expectBody()
			.jsonPath("$.type").isEqualTo("message")
			.jsonPath("$.role").isEqualTo("assistant")
			.jsonPath("$.model").isEqualTo("fake-small")
			.jsonPath("$.id").value<String> { assertTrue(it.startsWith("msg_")) }
			.jsonPath("$.content[0].type").isEqualTo("text")
			.jsonPath("$.content[0].text").isEqualTo(FakeText.content(8))
			.jsonPath("$.stop_reason").isEqualTo("end_turn")
			.jsonPath("$.stop_sequence").isEmpty
			.jsonPath("$.usage.input_tokens").isEqualTo(100)
			.jsonPath("$.usage.cache_creation_input_tokens").isEqualTo(0)
			.jsonPath("$.usage.cache_read_input_tokens").isEqualTo(20)
			.jsonPath("$.usage.output_tokens").isEqualTo(8)
	}

	@Test
	fun `max_tokens below the directed output caps the text and stops with max_tokens`() {
		post(anthropicBody("fake-small:out=8", maxTokens = 3))
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.content[0].text").isEqualTo(FakeText.content(3))
			.jsonPath("$.stop_reason").isEqualTo("max_tokens")
			.jsonPath("$.usage.output_tokens").isEqualTo(3)
	}

	@Test
	fun `stream events follow the documented order with cumulative usage`() {
		val result = readSse(port, path, anthropicBody("fake-small:in=120,cached=20,out=3", stream = true))

		assertEquals(HttpStatus.OK, result.status)
		assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(result.contentType), "content type was ${result.contentType}")
		assertNull(result.error)
		val frames = result.frames
		val expectedEvents = listOf(
			"message_start", "content_block_start", "ping",
			"content_block_delta", "content_block_delta", "content_block_delta",
			"content_block_stop", "message_delta", "message_stop",
		)
		assertEquals(expectedEvents, frames.map { it.sseEvent() })
		frames.forEach { frame -> assertEquals(frame.sseEvent(), mapper.json(frame).path("type").stringValue()) }

		val start = mapper.json(frames[0]).path("message")
		assertTrue(start.path("id").stringValue().startsWith("msg_"))
		assertEquals("fake-small", start.path("model").stringValue())
		assertEquals(0, start.path("content").size())
		assertTrue(start.path("stop_reason").isNull)
		assertEquals(100, start.path("usage").path("input_tokens").intValue())
		assertEquals(20, start.path("usage").path("cache_read_input_tokens").intValue())
		assertEquals(1, start.path("usage").path("output_tokens").intValue())

		val blockStart = mapper.json(frames[1])
		assertEquals(0, blockStart.path("index").intValue())
		assertEquals("text", blockStart.path("content_block").path("type").stringValue())
		assertEquals("", blockStart.path("content_block").path("text").stringValue())

		for (i in 0 until 3) {
			val delta = mapper.json(frames[3 + i]).path("delta")
			assertEquals("text_delta", delta.path("type").stringValue())
			assertEquals(FakeText.chunk(i), delta.path("text").stringValue())
		}
		val messageDelta = mapper.json(frames[7])
		assertEquals("end_turn", messageDelta.path("delta").path("stop_reason").stringValue())
		assertTrue(messageDelta.path("delta").path("stop_sequence").isNull)
		assertEquals(3, messageDelta.path("usage").path("output_tokens").intValue())
		assertFalse(messageDelta.path("usage").has("input_tokens"))
	}

	@Test
	fun `err directive answers with that status in the Anthropic error shape`() {
		val requestId = post(anthropicBody("fake-small:err=529"))
			.expectStatus().isEqualTo(529)
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("overloaded_error")
			.jsonPath("$.error.message").isEqualTo("Overloaded")
			.jsonPath("$.request_id").value<String> { assertTrue(it.startsWith("req_")) }
			.returnResult()
		val headerId = requestId.responseHeaders.getFirst(REQUEST_ID_HEADER)
		assertEquals(headerId, mapper.readTree(requestId.responseBody!!).path("request_id").stringValue())
	}

	@Test
	fun `streamerr sends an error event and ends without message_stop`() {
		val result = readSse(port, path, anthropicBody("fake-small:out=5,streamerr=2", stream = true))

		assertNull(result.error)
		val frames = result.frames
		assertEquals(listOf("message_start", "content_block_start", "ping", "content_block_delta", "content_block_delta", "error"), frames.map { it.sseEvent() })
		val error = mapper.json(frames.last())
		assertEquals("error", error.path("type").stringValue())
		assertEquals("overloaded_error", error.path("error").path("type").stringValue())
		assertEquals("Overloaded", error.path("error").path("message").stringValue())
	}

	@Test
	fun `abort closes the connection after the given chunk with no terminating event`() {
		val result = readSse(port, path, anthropicBody("fake-small:out=5,abort=2", stream = true))

		assertEquals(HttpStatus.OK, result.status)
		assertNotNull(result.error, "the client must see the connection cut, got frames ${result.frames}")
		assertEquals(listOf("message_start", "content_block_start", "ping", "content_block_delta", "content_block_delta"), result.frames.map { it.sseEvent() })
		assertFalse(result.raw.contains("message_stop"))
	}

	@Test
	fun `max_tokens and messages are required`() {
		post(anthropicBody("fake-small", maxTokens = null))
			.expectStatus().isBadRequest
			.expectHeader().exists(REQUEST_ID_HEADER)
			.expectBody()
			.jsonPath("$.type").isEqualTo("error")
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.message").isEqualTo("max_tokens: Field required")
			.jsonPath("$.request_id").value<String> { assertTrue(it.startsWith("req_")) }
		post(mapOf("model" to "fake-small", "max_tokens" to 10))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.message").isEqualTo("messages: Field required")
	}
}
