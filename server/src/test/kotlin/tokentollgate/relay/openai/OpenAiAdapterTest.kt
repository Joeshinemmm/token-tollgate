package tokentollgate.relay.openai

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tokentollgate.pricing.TokenUsage
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiAdapterTest {

	private val mapper = JsonMapper.builder().build()
	private val adapter = OpenAiAdapter(OpenAiRequestInspector(mapper), mapper)

	@Test
	fun `a plain EXAONE response yields its model and usage`() {
		// Shortened from the K-EXAONE 2.5 response example: the answer and the reasoning arrive as separate fields.
		val body = """
			{
			  "id": "1f30a3635ebd4e4cb57e4d41e2f18d55",
			  "choices": [{"finish_reason": "stop", "index": 0, "logprobs": null,
			    "message": {"content": "\n\nHello! How can I help you today?", "refusal": null, "role": "assistant", "tool_calls": null, "reasoning_content": "We need answer to user."},
			    "matched_stop": 53}],
			  "created": 1793945790,
			  "model": "k-exaone_v2.5",
			  "object": "chat.completion",
			  "metadata": {"weight_version": "default"},
			  "moderation": null,
			  "service_tier": null,
			  "system_fingerprint": null,
			  "usage": {"completion_tokens": 218, "prompt_tokens": 22, "total_tokens": 240, "completion_tokens_details": null, "prompt_tokens_details": null, "reasoning_tokens": 209}
			}
		""".trimIndent().toByteArray()

		val outcome = adapter.parsePlain(HttpStatus.OK, body)

		assertEquals("k-exaone_v2.5", outcome.modelServed)
		assertEquals(TokenUsage(inputTokens = 22, outputTokens = 218, cachedInputTokens = 0), outcome.usage)
		assertNull(outcome.errorType)
	}

	@Test
	fun `an OpenAI error body gives its error type`() {
		val body = """{"error": {"message": "Rate limit reached for requests", "type": "rate_limit_error", "param": null, "code": null}}""".toByteArray()

		val outcome = adapter.parsePlain(HttpStatus.TOO_MANY_REQUESTS, body)

		assertEquals("rate_limit_error", outcome.errorType)
		assertNull(outcome.usage)
	}

	@Test
	fun `an EXAONE error body has no error type, so the relay records the status instead`() {
		// EXAONE errors are {"result_code", "description"} rather than OpenAI's error object.
		val body = """{"result_code": 429, "description": "Too many requests."}""".toByteArray()

		val outcome = adapter.parsePlain(HttpStatus.TOO_MANY_REQUESTS, body)

		assertNull(outcome.errorType)
		assertNull(outcome.usage)
		assertNull(outcome.modelServed)
	}

	@Test
	fun `a body that is not a JSON object yields nothing`() {
		assertEquals(null, adapter.parsePlain(HttpStatus.OK, "upstream said no".toByteArray()).usage)
		assertEquals(null, adapter.parsePlain(HttpStatus.BAD_GATEWAY, ByteArray(0)).errorType)
		assertEquals(null, adapter.parsePlain(HttpStatus.OK, "[1, 2]".toByteArray()).modelServed)
	}
}
