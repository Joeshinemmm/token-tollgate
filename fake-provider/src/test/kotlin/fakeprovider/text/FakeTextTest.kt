package fakeprovider.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FakeTextTest {

	@Test
	fun `content has exactly the requested number of words`() {
		assertEquals("", FakeText.content(0))
		assertEquals("lorem", FakeText.content(1))
		assertEquals("lorem ipsum dolor", FakeText.content(3))
		assertEquals(40, FakeText.content(40).split(' ').size)
	}

	@Test
	fun `chunks join into the same content and cycle through the word list`() {
		val joined = (0 until 25).joinToString("") { FakeText.chunk(it) }
		assertEquals(FakeText.content(25), joined)
		assertEquals("lorem", FakeText.chunk(0))
		assertEquals(" lorem", FakeText.chunk(19))
		assertEquals(FakeText.content(25), FakeText.content(25))
	}
}
