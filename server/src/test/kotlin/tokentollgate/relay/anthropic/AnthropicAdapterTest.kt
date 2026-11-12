package tokentollgate.relay.anthropic

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import tokentollgate.json
import tokentollgate.relay.GatewayError
import tokentollgate.relay.RelayErrors
import tokentollgate.upstream.ModelPattern
import tokentollgate.upstream.Provider
import tokentollgate.upstream.ProviderType
import tokentollgate.upstream.UpstreamSettings
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicAdapterTest {

	private val mapper = JsonMapper.builder().build()
	private val adapter = AnthropicAdapter(AnthropicRequestInspector(mapper), mapper)
	private val provider = Provider("anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com", apiKey = null, apiKeyEnv = "ANTHROPIC_API_KEY", anonymous = false, default = true, patterns = listOf(ModelPattern("claude-*")), settings = UpstreamSettings())

	@Test
	fun `anthropic-version is added only when the client did not send one`() {
		val missing = HttpHeaders()
		adapter.prepareUpstreamHeaders(missing)
		assertEquals(AnthropicAdapter.DEFAULT_VERSION, missing.getFirst("anthropic-version"))

		val present = HttpHeaders()
		present.set("anthropic-version", "2026-10-21")
		adapter.prepareUpstreamHeaders(present)
		assertEquals(listOf("2026-10-21"), present.get("anthropic-version"))
	}

	@Test
	fun `gateway errors take the documented Anthropic types and carry the request id`() {
		val requestId = UUID.randomUUID()
		val notFound = adapter.errorBody(RelayErrors.modelNotFound("x"), requestId)
		assertEquals("error", notFound["type"])
		assertEquals("not_found_error", errorField(notFound, "type"))
		assertTrue(errorField(notFound, "message")!!.contains("model_not_found"))
		assertEquals(requestId.toString(), notFound["request_id"])

		assertEquals("api_error", errorField(adapter.errorBody(RelayErrors.providerDisabled(provider), requestId), "type"))
		assertEquals("api_error", errorField(adapter.errorBody(RelayErrors.upstreamUnreachable(provider), requestId), "type"))
		assertEquals("timeout_error", errorField(adapter.errorBody(RelayErrors.upstreamTimeout(provider), requestId), "type"))
		assertEquals("invalid_request_error", errorField(adapter.errorBody(RelayErrors.badRequest("bad", null), requestId), "type"))
		assertEquals("api_error", errorField(adapter.errorBody(GatewayError(HttpStatus.INTERNAL_SERVER_ERROR, "boom", "server_error"), requestId), "type"))
	}

	@Test
	fun `the stream error event has the documented shape and no request id`() {
		val frame = String(adapter.errorFrame(RelayErrors.streamInterrupted(provider), UUID.randomUUID()), Charsets.UTF_8)
		assertTrue(frame.startsWith("event: error\ndata: "))
		assertTrue(frame.endsWith("\n\n"))
		val data = mapper.json(frame)
		assertEquals("error", data.path("type").stringValue())
		assertEquals("api_error", data.path("error").path("type").stringValue())
		assertTrue(data.path("error").path("message").stringValue().contains("stream_interrupted"))
		assertFalse(data.has("request_id"))
	}

	@Test
	fun `plain responses yield normalized usage or the upstream error type`() {
		val ok = adapter.parsePlain(HttpStatus.OK, """{"id":"msg_1","type":"message","model":"fake-relay","usage":{"input_tokens":100,"cache_creation_input_tokens":0,"cache_read_input_tokens":20,"output_tokens":8}}""".toByteArray())
		assertEquals("fake-relay", ok.modelServed)
		assertEquals(120, ok.usage?.inputTokens)
		assertEquals(20, ok.usage?.cachedInputTokens)
		assertNull(ok.errorType)

		val failed = adapter.parsePlain(HttpStatusCode.valueOf(529), """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_1"}""".toByteArray())
		assertNull(failed.usage)
		assertEquals("overloaded_error", failed.errorType)
	}

	private fun errorField(body: Map<String, Any?>, field: String): String? = (body["error"] as Map<*, *>)[field] as String?
}
