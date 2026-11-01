package fakeprovider.text

/** Deterministic filler text: token i is always the same word, so a given `out` yields the same content. */
object FakeText {

	private val WORDS = listOf(
		"lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
		"eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua",
	)

	fun word(index: Int): String = WORDS[index % WORDS.size]

	/** The text of stream chunk [index]: a leading space joins it to the previous chunk. */
	fun chunk(index: Int): String = if (index == 0) word(0) else " " + word(index)

	fun content(tokens: Int): String = buildString {
		for (i in 0 until tokens) append(chunk(i))
	}
}
