package tokentollgate.relay.openai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiRequestInspectorTest {

	private val mapper = JsonMapper.builder().build()
	private val inspector = OpenAiRequestInspector(mapper)

	@Test
	fun `a plain request goes upstream byte for byte`() {
		val body = """{"model": "fake-small", "messages": [{"role": "user", "content": "hi"}], "temperature": 0.2}""".toByteArray()
		val inspected = inspector.inspect(body)
		assertEquals("fake-small", inspected.model)
		assertFalse(inspected.stream)
		assertFalse(inspected.includeUsage)
		assertContentEquals(body, inspected.upstreamBody)
	}

	@Test
	fun `a streaming request without include_usage gets it injected and keeps its other fields`() {
		val body = """{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"hi"}],"temperature":0.2}""".toByteArray()
		val inspected = inspector.inspect(body)
		assertTrue(inspected.stream)
		assertFalse(inspected.includeUsage)
		val sent = mapper.readTree(inspected.upstreamBody)
		assertTrue(sent.path("stream_options").path("include_usage").asBoolean(false))
		assertEquals(0.2, sent.path("temperature").asDouble(), 0.0)
		assertEquals("hi", sent.path("messages").path(0).path("content").stringValue())
	}

	@Test
	fun `an existing stream_options object is extended and include_usage true is left alone`() {
		val extended = inspector.inspect("""{"model":"m","stream":true,"stream_options":{"include_obfuscation":false},"messages":[{}]}""".toByteArray())
		val sent = mapper.readTree(extended.upstreamBody).path("stream_options")
		assertTrue(sent.path("include_usage").asBoolean(false))
		assertFalse(sent.path("include_obfuscation").asBoolean(true))

		val body = """{"model":"m","stream":true,"stream_options":{"include_usage":true},"messages":[{}]}""".toByteArray()
		val asked = inspector.inspect(body)
		assertTrue(asked.includeUsage)
		assertContentEquals(body, asked.upstreamBody)
	}

	@Test
	fun `missing model and malformed JSON are rejected`() {
		val noModel = assertThrows<InvalidRelayRequestException> { inspector.inspect("""{"messages":[]}""".toByteArray()) }
		assertEquals("model", noModel.param)
		assertThrows<InvalidRelayRequestException> { inspector.inspect("{not json".toByteArray()) }
		assertThrows<InvalidRelayRequestException> { inspector.inspect("[1,2]".toByteArray()) }
		assertThrows<InvalidRelayRequestException> { inspector.inspect(ByteArray(0)) }
	}
}
