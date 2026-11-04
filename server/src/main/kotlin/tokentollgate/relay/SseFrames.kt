package tokentollgate.relay

/** One server-sent event as it arrived: its bytes untouched, plus its lines for inspection. */
class SseFrame(val raw: ByteArray, val lines: List<String>) {

	/** The `data:` payload (several `data:` lines join with newlines, as the SSE spec says), or null for comment-only frames. */
	val data: String? = lines
		.filter { it.startsWith("data:") }
		.takeIf { it.isNotEmpty() }
		?.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
}

/**
 * Splits a byte stream into frames at blank lines (`\n\n` or `\r\n\r\n`), however the bytes are chunked.
 * Each frame keeps exactly the bytes it was made of, so forwarding frames reproduces the upstream stream.
 */
class SseFrameSplitter {

	private var buffer = ByteArray(INITIAL_CAPACITY)
	private var length = 0
	private var scanned = 0

	fun feed(bytes: ByteArray): List<SseFrame> {
		append(bytes)
		val frames = mutableListOf<SseFrame>()
		var start = 0
		var i = scanned
		while (i < length) {
			if (buffer[i] == NEWLINE) {
				if (i + 1 >= length) break
				if (buffer[i + 1] == NEWLINE) {
					frames += frame(start, i + 2)
					start = i + 2
					i = start
					continue
				}
				if (buffer[i + 1] == CARRIAGE_RETURN) {
					if (i + 2 >= length) break
					if (buffer[i + 2] == NEWLINE) {
						frames += frame(start, i + 3)
						start = i + 3
						i = start
						continue
					}
				}
			}
			i++
		}
		if (start > 0) {
			System.arraycopy(buffer, start, buffer, 0, length - start)
			length -= start
			i -= start
		}
		scanned = i
		return frames
	}

	/** Whatever is left when the stream ends without a terminating blank line, or null. */
	fun finish(): SseFrame? {
		if (length == 0) return null
		val frame = frame(0, length)
		length = 0
		scanned = 0
		return frame
	}

	private fun frame(start: Int, end: Int): SseFrame {
		val raw = buffer.copyOfRange(start, end)
		val text = String(raw, Charsets.UTF_8).trimEnd('\n', '\r')
		val lines = if (text.isEmpty()) emptyList() else text.split('\n').map { it.trimEnd('\r') }
		return SseFrame(raw, lines)
	}

	private fun append(bytes: ByteArray) {
		if (length + bytes.size > buffer.size) {
			buffer = buffer.copyOf(maxOf(buffer.size * 2, length + bytes.size))
		}
		System.arraycopy(bytes, 0, buffer, length, bytes.size)
		length += bytes.size
	}

	companion object {
		private const val INITIAL_CAPACITY = 4096
		private const val NEWLINE = '\n'.code.toByte()
		private const val CARRIAGE_RETURN = '\r'.code.toByte()
	}
}
