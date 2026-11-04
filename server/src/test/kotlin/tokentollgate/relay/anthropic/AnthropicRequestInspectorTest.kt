package tokentollgate.relay.anthropic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tokentollgate.relay.InvalidRelayRequestException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicRequestInspectorTest {

	private val inspector = AnthropicRequestInspector(JsonMapper.builder().build())

	@Test
	fun `the body goes upstream byte for byte, streaming or not`() {
		val plain = """{"model": "fake-relay", "max_tokens": 64, "messages": [{"role": "user", "content": "hi"}]}""".toByteArray()
		val inspected = inspector.inspect(plain)
		assertEquals("fake-relay", inspected.model)
		assertFalse(inspected.stream)
		assertFalse(inspected.includeUsage)
		assertContentEquals(plain, inspected.upstreamBody)

		val streaming = """{"model":"fake-relay","stream":true,"max_tokens":64,"messages":[{}]}""".toByteArray()
		val streamed = inspector.inspect(streaming)
		assertTrue(streamed.stream)
		assertContentEquals(streaming, streamed.upstreamBody)
	}

	@Test
	fun `max_tokens is not checked here`() {
		assertEquals("fake-relay", inspector.inspect("""{"model":"fake-relay","messages":[{}]}""".toByteArray()).model)
	}

	@Test
	fun `missing model and malformed bodies are rejected`() {
		val noModel = assertThrows<InvalidRelayRequestException> { inspector.inspect("""{"max_tokens":64,"messages":[]}""".toByteArray()) }
		assertEquals("model", noModel.param)
		assertEquals("model: Field required", noModel.message)
		assertThrows<InvalidRelayRequestException> { inspector.inspect("{nope".toByteArray()) }
		assertThrows<InvalidRelayRequestException> { inspector.inspect("[]".toByteArray()) }
		assertThrows<InvalidRelayRequestException> { inspector.inspect(ByteArray(0)) }
	}
}
