package tokentollgate.relay.anthropic

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicUsageParserTest {

	private val mapper = JsonMapper.builder().build()

	@Test
	fun `the three input counts are summed and cache reads kept apart`() {
		val usage = AnthropicUsageParser.parse(mapper.readTree("""{"input_tokens":100,"cache_creation_input_tokens":5,"cache_read_input_tokens":20,"output_tokens":8}"""))
		assertEquals(125, usage?.inputTokens)
		assertEquals(20, usage?.cachedInputTokens)
		assertEquals(8, usage?.outputTokens)
	}

	@Test
	fun `missing or null cache fields count as zero and a missing input or output means no usage`() {
		val plain = AnthropicUsageParser.parse(mapper.readTree("""{"input_tokens":25,"output_tokens":15,"cache_read_input_tokens":null}"""))
		assertEquals(25, plain?.inputTokens)
		assertEquals(0, plain?.cachedInputTokens)
		assertNull(AnthropicUsageParser.parse(mapper.readTree("""{"output_tokens":15}""")))
		assertNull(AnthropicUsageParser.parse(mapper.readTree("""{"input_tokens":25}""")))
		assertNull(AnthropicUsageParser.parse(mapper.readTree("[]")))
	}

	@Test
	fun `message_delta overrides only the fields it carries`() {
		val accumulator = AnthropicUsageParser.Accumulator()
		accumulator.start(mapper.readTree("""{"input_tokens":100,"cache_read_input_tokens":20,"output_tokens":1}"""))
		assertEquals(1, accumulator.toUsage()?.outputTokens)
		accumulator.delta(mapper.readTree("""{"output_tokens":8}"""))
		assertEquals(8, accumulator.toUsage()?.outputTokens)
		assertEquals(120, accumulator.toUsage()?.inputTokens)
		accumulator.delta(mapper.readTree("""{"input_tokens":130,"output_tokens":9}"""))
		assertEquals(150, accumulator.toUsage()?.inputTokens)
		assertEquals(9, accumulator.toUsage()?.outputTokens)
		accumulator.delta(mapper.readTree("""{}"""))
		assertEquals(9, accumulator.toUsage()?.outputTokens)
	}
}
