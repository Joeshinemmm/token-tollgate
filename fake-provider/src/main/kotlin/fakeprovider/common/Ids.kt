package fakeprovider.common

import kotlin.random.Random

object Ids {
	private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

	private fun random(prefix: String, length: Int = 24): String = buildString(prefix.length + length) {
		append(prefix)
		repeat(length) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) }
	}

	fun chatCompletion(): String = random("chatcmpl-")
	fun message(): String = random("msg_")
	fun request(): String = random("req_")
}
