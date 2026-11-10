package tokentollgate.relay.openai

import org.junit.jupiter.api.Test
import tokentollgate.relay.SseFrame
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiStreamObserverTest {

	private val mapper = JsonMapper.builder().build()

	private fun frame(data: String) = SseFrame("data: $data\n\n".toByteArray(), listOf("data: $data"))

	private val role = frame("""{"id":"c1","object":"chat.completion.chunk","model":"fake-small","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}""")
	private val content = frame("""{"id":"c1","object":"chat.completion.chunk","model":"fake-small","choices":[{"index":0,"delta":{"content":"lorem"},"finish_reason":null}],"usage":null}""")
	private val usageChunk = frame("""{"id":"c1","object":"chat.completion.chunk","model":"fake-small","choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128,"prompt_tokens_details":{"cached_tokens":20}}}""")

	@Test
	fun `the usage chunk is read and dropped unless the client asked for it`() {
		val observer = OpenAiStreamObserver(mapper, forwardUsageChunk = false)
		assertTrue(observer.observe(role))
		assertNull(observer.firstContentNanos)
		assertTrue(observer.observe(content))
		assertNotNull(observer.firstContentNanos)
		assertFalse(observer.observe(usageChunk))
		assertEquals(120, observer.usage?.inputTokens)
		assertEquals(20, observer.usage?.cachedInputTokens)
		assertEquals(8, observer.usage?.outputTokens)
		assertEquals("fake-small", observer.model)
		assertTrue(observer.observe(frame("[DONE]")))
		assertTrue(observer.done)

		assertTrue(OpenAiStreamObserver(mapper, forwardUsageChunk = true).observe(usageChunk))
	}

	@Test
	fun `an error frame is forwarded and remembered`() {
		val observer = OpenAiStreamObserver(mapper, forwardUsageChunk = false)
		assertTrue(observer.observe(frame("""{"error":{"message":"boom","type":"server_error","param":null,"code":null}}""")))
		assertTrue(observer.sawError)
		assertEquals("server_error", observer.errorType)
		assertFalse(observer.done)
	}

	@Test
	fun `frames that are not JSON objects pass through untouched`() {
		val observer = OpenAiStreamObserver(mapper, forwardUsageChunk = false)
		assertTrue(observer.observe(SseFrame(": ping\n\n".toByteArray(), listOf(": ping"))))
		assertTrue(observer.observe(frame("not json at all")))
		assertTrue(observer.observe(frame("[1,2,3]")))
		assertNull(observer.usage)
		assertFalse(observer.sawError)
	}

	@Test
	fun `EXAONE reasoning deltas are not first content and its usage chunk is read and dropped`() {
		// Frames from the K-EXAONE 2.5 streaming example: role, reasoning, answer, finish, usage, [DONE].
		fun chunk(rest: String) = frame("""{"id": "2fa2bf0467cf4d758fe4c4dba28f2b18", $rest, "created": 1793947427, "model": "k-exaone_v2.5", "object": "chat.completion.chunk"}""")
		val observer = OpenAiStreamObserver(mapper, forwardUsageChunk = false)

		assertTrue(observer.observe(chunk(""""choices": [{"delta": {"content": "", "role": "assistant"}, "index": 0}]""")))
		assertTrue(observer.observe(chunk(""""choices": [{"delta": {"reasoning_content": "We"}, "index": 0}]""")))
		assertTrue(observer.observe(chunk(""""choices": [{"delta": {"reasoning_content": " need"}, "index": 0}]""")))
		assertNull(observer.firstContentNanos)

		assertTrue(observer.observe(chunk(""""choices": [{"delta": {"content": "how"}, "index": 0}]""")))
		assertNotNull(observer.firstContentNanos)
		assertTrue(observer.observe(chunk(""""choices": [{"delta": {}, "finish_reason": "stop", "index": 0, "matched_stop": 53}]""")))

		val usageChunk = frame("""{"id": "2fa2bf0467cf4d758fe4c4dba28f2b18", "choices": [], "created": 1793947427, "model": "k-exaone_v2.5", "object": "chat.completion.chunk", "usage": {"completion_tokens": 234, "prompt_tokens": 22, "total_tokens": 256, "reasoning_tokens": 216}}""")
		assertFalse(observer.observe(usageChunk))
		assertEquals(22, observer.usage?.inputTokens)
		assertEquals(234, observer.usage?.outputTokens)
		assertEquals(0, observer.usage?.cachedInputTokens)
		assertEquals("k-exaone_v2.5", observer.model)

		assertTrue(observer.observe(frame("[DONE]")))
		assertTrue(observer.done)
		assertFalse(observer.sawError)

		assertTrue(OpenAiStreamObserver(mapper, forwardUsageChunk = true).observe(usageChunk))
	}

	@Test
	fun `tool call deltas count as first content`() {
		val observer = OpenAiStreamObserver(mapper, forwardUsageChunk = false)
		observer.observe(frame("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"f","arguments":""}}]}}]}"""))
		assertNotNull(observer.firstContentNanos)
	}
}
