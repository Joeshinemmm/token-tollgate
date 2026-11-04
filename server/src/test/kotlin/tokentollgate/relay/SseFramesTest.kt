package tokentollgate.relay

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseFramesTest {

	private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

	@Test
	fun `frames end at blank lines and keep their bytes`() {
		val splitter = SseFrameSplitter()
		val input = "data: {\"a\":1}\n\ndata: [DONE]\n\n"
		val frames = splitter.feed(bytes(input))
		assertEquals(listOf("{\"a\":1}", "[DONE]"), frames.map { it.data })
		assertContentEquals(bytes(input), frames.flatMap { it.raw.toList() }.toByteArray())
		assertNull(splitter.finish())
	}

	@Test
	fun `a frame split across buffers is reassembled`() {
		val splitter = SseFrameSplitter()
		assertEquals(0, splitter.feed(bytes("data: {\"a\":")).size)
		assertEquals(0, splitter.feed(bytes("1}\n")).size)
		val frames = splitter.feed(bytes("\ndata: second\n\n"))
		assertEquals(listOf("{\"a\":1}", "second"), frames.map { it.data })
	}

	@Test
	fun `CRLF line endings and a frame split right before the second CR`() {
		val splitter = SseFrameSplitter()
		assertEquals(0, splitter.feed(bytes("data: x\r\n")).size)
		assertEquals(0, splitter.feed(bytes("\r")).size)
		val frames = splitter.feed(bytes("\ndata: y\r\n\r\n"))
		assertEquals(listOf("x", "y"), frames.map { it.data })
		assertContentEquals(bytes("data: x\r\n\r\n"), frames[0].raw)
	}

	@Test
	fun `comment frames have no data and multi-line data joins with newlines`() {
		val splitter = SseFrameSplitter()
		val frames = splitter.feed(bytes(": keep-alive\n\ndata: line one\ndata:line two\n\n"))
		assertEquals(2, frames.size)
		assertNull(frames[0].data)
		assertEquals(listOf(": keep-alive"), frames[0].lines)
		assertEquals("line one\nline two", frames[1].data)
	}

	@Test
	fun `leftover bytes without a terminating blank line come out of finish`() {
		val splitter = SseFrameSplitter()
		assertEquals(0, splitter.feed(bytes("data: partial")).size)
		val last = splitter.finish()
		assertEquals("partial", last?.data)
		assertContentEquals(bytes("data: partial"), last?.raw)
		assertNull(splitter.finish())
	}
}
