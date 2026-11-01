package tokentollgate.keys

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyGeneratorTest {

	private val base62 = Regex("^[0-9A-Za-z]+$")

	@Test
	fun `keys have the tt_ prefix and 32 base62 characters`() {
		val key = KeyGenerator.generate()
		assertTrue(key.startsWith("tt_"), key)
		assertEquals(35, key.length)
		assertTrue(base62.matches(key.removePrefix("tt_")), key)
	}

	@Test
	fun `display prefix is the first eight characters`() {
		assertEquals("tt_ABCDE", KeyGenerator.displayPrefix("tt_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
	}

	@Test
	fun `hash is lowercase SHA-256 hex and deterministic`() {
		val hash = KeyGenerator.hash("tt_example")
		assertEquals(64, hash.length)
		assertTrue(Regex("^[0-9a-f]+$").matches(hash), hash)
		assertEquals(hash, KeyGenerator.hash("tt_example"))
		assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			KeyGenerator.hash(""),
			"SHA-256 of the empty string is a published test vector",
		)
	}

	@Test
	fun `a thousand generated keys are all distinct`() {
		val keys = (1..1000).map { KeyGenerator.generate() }.toSet()
		assertEquals(1000, keys.size)
	}
}
