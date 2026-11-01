package fakeprovider.directive

import fakeprovider.FakeProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectiveParserTest {

	private val parser = DirectiveParser(FakeProperties(defaults = FakeProperties.Defaults(inputTokens = 100, cachedInputTokens = 5, outputTokens = 50, ttftMs = 7, chunkIntervalMs = 3)))

	@Test
	fun `a plain model name takes every default`() {
		val directive = parser.parse("fake-small")
		assertEquals(Directive("fake-small", inputTokens = 100, cachedInputTokens = 5, outputTokens = 50, ttftMs = 7, gapMs = 3), directive)
	}

	@Test
	fun `every key overrides its default`() {
		val directive = parser.parse("fake-small:in=120,cached=20,out=8,ttft=200,gap=10,err=429,abort=5,streamerr=2")
		assertEquals("fake-small", directive.model)
		assertEquals(120, directive.inputTokens)
		assertEquals(20, directive.cachedInputTokens)
		assertEquals(8, directive.outputTokens)
		assertEquals(200, directive.ttftMs)
		assertEquals(10, directive.gapMs)
		assertEquals(429, directive.errorStatus)
		assertEquals(5, directive.abortAfter)
		assertEquals(2, directive.streamErrorAfter)
	}

	@Test
	fun `streamerr without a value means after the first chunk`() {
		assertEquals(1, parser.parse("fake:streamerr").streamErrorAfter)
		assertNull(parser.parse("fake:out=3").streamErrorAfter)
	}

	@Test
	fun `spaces around names and values are ignored`() {
		val directive = parser.parse(" fake-small : in = 30 , out = 4 ")
		assertEquals("fake-small", directive.model)
		assertEquals(30, directive.inputTokens)
		assertEquals(4, directive.outputTokens)
	}

	@Test
	fun `unknown keys are rejected by name`() {
		val ex = assertThrows<DirectiveException> { parser.parse("fake:bogus=1") }
		assertEquals("unknown directive 'bogus'", ex.message)
	}

	@Test
	fun `values must be non-negative integers`() {
		assertEquals("directive 'in' must be a non-negative integer", assertThrows<DirectiveException> { parser.parse("fake:in=abc") }.message)
		assertEquals("directive 'out' must be a non-negative integer", assertThrows<DirectiveException> { parser.parse("fake:out=-1") }.message)
		assertEquals("directive 'in' needs a value", assertThrows<DirectiveException> { parser.parse("fake:in") }.message)
		assertEquals("directive 'out' must be at most 200000", assertThrows<DirectiveException> { parser.parse("fake:out=200001") }.message)
	}

	@Test
	fun `cached cannot exceed in`() {
		assertEquals("cached must not exceed in", assertThrows<DirectiveException> { parser.parse("fake:in=10,cached=11") }.message)
		assertEquals(10, parser.parse("fake:in=10,cached=10").cachedInputTokens)
	}

	@Test
	fun `err must be an error status and abort and streamerr at least 1`() {
		assertEquals("err must be an HTTP status between 400 and 599", assertThrows<DirectiveException> { parser.parse("fake:err=200") }.message)
		assertEquals("abort must be at least 1", assertThrows<DirectiveException> { parser.parse("fake:abort=0") }.message)
		assertEquals("streamerr must be at least 1", assertThrows<DirectiveException> { parser.parse("fake:streamerr=0") }.message)
	}

	@Test
	fun `the model name must not be blank`() {
		assertEquals("model must not be blank", assertThrows<DirectiveException> { parser.parse(":in=3") }.message)
		assertEquals("model must not be blank", assertThrows<DirectiveException> { parser.parse("   ") }.message)
	}
}
