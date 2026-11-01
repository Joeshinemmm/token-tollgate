package tokentollgate.keys

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Virtual keys look like `tt_` followed by 32 base62 characters (about 190 bits of entropy).
 * Only the SHA-256 hash and a short display prefix are ever stored.
 */
object KeyGenerator {
	const val PREFIX = "tt_"
	const val RANDOM_LENGTH = 32
	const val DISPLAY_PREFIX_LENGTH = 8

	private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	private val random = SecureRandom()

	fun generate(): String = buildString(PREFIX.length + RANDOM_LENGTH) {
		append(PREFIX)
		repeat(RANDOM_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
	}

	fun displayPrefix(key: String): String = key.take(DISPLAY_PREFIX_LENGTH)

	fun hash(key: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(key.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
}
