package fakeprovider.common

import fakeprovider.directive.Directive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class StreamScriptTest {

	private fun directive(abortAfter: Int? = null, streamErrorAfter: Int? = null) =
		Directive("fake", inputTokens = 10, cachedInputTokens = 0, outputTokens = 3, ttftMs = 0, gapMs = 0, abortAfter = abortAfter, streamErrorAfter = streamErrorAfter)

	private fun frames(directive: Directive) = streamFrames(
		directive = directive,
		outputTokens = directive.outputTokens,
		head = listOf("head"),
		contentFrame = { "content$it" },
		tail = listOf("tail1", "tail2"),
		errorFrame = "error",
	)

	@Test
	fun `a normal stream is head, content, tail`() = runBlocking<Unit> {
		assertEquals(listOf("head", "content0", "content1", "content2", "tail1", "tail2"), frames(directive()).toList())
	}

	@Test
	fun `streamerr replaces the rest of the stream with the error frame`() = runBlocking<Unit> {
		assertEquals(listOf("head", "content0", "content1", "error"), frames(directive(streamErrorAfter = 2)).toList())
	}

	@Test
	fun `abort raises after the given chunk so nothing more is written`() = runBlocking<Unit> {
		val seen = mutableListOf<String>()
		assertThrows<StreamAbortedException> { frames(directive(abortAfter = 2)).collect { seen.add(it) } }
		assertEquals(listOf("head", "content0", "content1"), seen)
	}

	@Test
	fun `abort and streamerr beyond the output count have no effect`() = runBlocking<Unit> {
		assertEquals(6, frames(directive(abortAfter = 9)).toList().size)
		assertEquals(6, frames(directive(streamErrorAfter = 9)).toList().size)
	}
}
