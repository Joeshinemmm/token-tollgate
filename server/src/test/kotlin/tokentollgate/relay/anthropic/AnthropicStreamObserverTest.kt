package tokentollgate.relay.anthropic

import org.junit.jupiter.api.Test
import tokentollgate.relay.SseFrame
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicStreamObserverTest {

	private val mapper = JsonMapper.builder().build()

	private fun event(type: String, data: String) = SseFrame("event: $type\ndata: $data\n\n".toByteArray(), listOf("event: $type", "data: $data"))

	private val start = event("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"fake-relay","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":100,"cache_creation_input_tokens":0,"cache_read_input_tokens":20,"output_tokens":1}}}""")
	private val blockStart = event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
	private val ping = event("ping", """{"type":"ping"}""")
	private val delta = event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lorem"}}""")
	private val messageDelta = event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":8}}""")
	private val stop = event("message_stop", """{"type":"message_stop"}""")

	@Test
	fun `a complete stream yields model, normalized usage, first content and done`() {
		val observer = AnthropicStreamObserver(mapper)
		listOf(start, blockStart, ping).forEach { assertTrue(observer.observe(it)) }
		assertEquals("fake-relay", observer.model)
		assertNull(observer.firstContentNanos)
		assertEquals(1, observer.usage?.outputTokens)
		assertTrue(observer.observe(delta))
		assertNotNull(observer.firstContentNanos)
		assertTrue(observer.observe(messageDelta))
		assertTrue(observer.observe(stop))
		assertTrue(observer.done)
		assertFalse(observer.sawError)
		assertEquals(120, observer.usage?.inputTokens)
		assertEquals(20, observer.usage?.cachedInputTokens)
		assertEquals(8, observer.usage?.outputTokens)
	}

	@Test
	fun `a message_delta without usage keeps the previous numbers`() {
		val observer = AnthropicStreamObserver(mapper)
		observer.observe(start)
		observer.observe(event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null}}"""))
		assertEquals(1, observer.usage?.outputTokens)
		assertEquals(120, observer.usage?.inputTokens)
	}

	@Test
	fun `without input tokens there is no usage until a delta supplies them`() {
		val observer = AnthropicStreamObserver(mapper)
		observer.observe(event("message_start", """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"stop_sequence":null}}"""))
		observer.observe(messageDelta)
		assertNull(observer.usage)
		observer.observe(event("message_delta", """{"type":"message_delta","delta":{},"usage":{"input_tokens":40,"output_tokens":9}}"""))
		assertEquals(40, observer.usage?.inputTokens)
		assertEquals(9, observer.usage?.outputTokens)
	}

	@Test
	fun `an error event is forwarded and remembered`() {
		val observer = AnthropicStreamObserver(mapper)
		observer.observe(start)
		assertTrue(observer.observe(event("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")))
		assertTrue(observer.sawError)
		assertEquals("overloaded_error", observer.errorType)
		assertFalse(observer.done)
	}

	@Test
	fun `unknown events, comments and unparseable frames pass through`() {
		val observer = AnthropicStreamObserver(mapper)
		assertTrue(observer.observe(event("future_event", """{"type":"future_event","payload":1}""")))
		assertTrue(observer.observe(SseFrame(": keep-alive\n\n".toByteArray(), listOf(": keep-alive"))))
		assertTrue(observer.observe(event("message_delta", "not json")))
		assertNull(observer.usage)
		assertFalse(observer.done)
		assertFalse(observer.sawError)
	}
}
