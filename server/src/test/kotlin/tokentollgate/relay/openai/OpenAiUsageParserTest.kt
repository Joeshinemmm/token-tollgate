package tokentollgate.relay.openai

import org.junit.jupiter.api.Test
import tokentollgate.pricing.TokenUsage
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiUsageParserTest {

	private val mapper = JsonMapper.builder().build()

	private fun parse(json: String): TokenUsage? = OpenAiUsageParser.parse(mapper.readTree(json))

	@Test
	fun `cached tokens come from prompt_tokens_details and stay inside the prompt total`() {
		val usage = parse("""{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128,"prompt_tokens_details":{"cached_tokens":20,"audio_tokens":0}}""")
		assertEquals(TokenUsage(inputTokens = 120, outputTokens = 8, cachedInputTokens = 20), usage)
	}

	@Test
	fun `EXAONE usage with null details and top-level reasoning tokens reads as input and output`() {
		// The usage object of the K-EXAONE 2.5 response example; its reasoning tokens are already inside completion_tokens.
		val usage = parse("""{"completion_tokens": 218, "prompt_tokens": 22, "total_tokens": 240, "completion_tokens_details": null, "prompt_tokens_details": null, "reasoning_tokens": 209}""")
		assertEquals(TokenUsage(inputTokens = 22, outputTokens = 218, cachedInputTokens = 0), usage)
	}

	@Test
	fun `usage without details has no cached tokens`() {
		val usage = parse("""{"completion_tokens": 234, "prompt_tokens": 22, "total_tokens": 256, "reasoning_tokens": 216}""")
		assertEquals(TokenUsage(inputTokens = 22, outputTokens = 234, cachedInputTokens = 0), usage)
	}

	@Test
	fun `anything without both token counts is not usage`() {
		assertNull(parse("null"))
		assertNull(parse("{}"))
		assertNull(parse("""{"prompt_tokens": 5}"""))
		assertNull(parse("""{"prompt_tokens": "5", "completion_tokens": "7"}"""))
		assertNull(parse("[1, 2]"))
	}
}
