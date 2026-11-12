package fakeprovider.openai

import fakeprovider.json
import fakeprovider.openAiBody
import fakeprovider.postJson
import fakeprovider.readSse
import fakeprovider.text.FakeText
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class OpenAiFakeApiTest {

	@Autowired
	lateinit var client: WebTestClient

	@Autowired
	lateinit var mapper: ObjectMapper

	@LocalServerPort
	var port: Int = 0

	private val path = OpenAiFakeController.PATH

	private fun post(body: Any) = client.postJson(path, body).exchange()

	@Test
	fun `completion reports the directed usage in the documented shape`() {
		post(openAiBody("fake-small:in=120,cached=20,out=8"))
			.expectStatus().isOk
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.object").isEqualTo("chat.completion")
			.jsonPath("$.model").isEqualTo("fake-small")
			.jsonPath("$.id").value<String> { assertTrue(it.startsWith("chatcmpl-")) }
			.jsonPath("$.created").isNumber
			.jsonPath("$.choices[0].index").isEqualTo(0)
			.jsonPath("$.choices[0].message.role").isEqualTo("assistant")
			.jsonPath("$.choices[0].message.content").isEqualTo(FakeText.content(8))
			.jsonPath("$.choices[0].message.refusal").isEmpty
			.jsonPath("$.choices[0].message.annotations").isArray
			.jsonPath("$.choices[0].logprobs").isEmpty
			.jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
			.jsonPath("$.usage.prompt_tokens").isEqualTo(120)
			.jsonPath("$.usage.completion_tokens").isEqualTo(8)
			.jsonPath("$.usage.total_tokens").isEqualTo(128)
			.jsonPath("$.usage.prompt_tokens_details.cached_tokens").isEqualTo(20)
			.jsonPath("$.usage.completion_tokens_details.reasoning_tokens").isEqualTo(0)
			.jsonPath("$.service_tier").isEqualTo("default")
			.jsonPath("$.system_fingerprint").isEqualTo(OpenAiShapes.SYSTEM_FINGERPRINT)
	}

	@Test
	fun `max_tokens below the directed output caps the text and ends with length`() {
		post(openAiBody("fake-small:out=8", maxTokens = 3))
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.choices[0].message.content").isEqualTo(FakeText.content(3))
			.jsonPath("$.choices[0].finish_reason").isEqualTo("length")
			.jsonPath("$.usage.completion_tokens").isEqualTo(3)
	}

	@Test
	fun `stream frames follow the documented order and carry no usage key by default`() {
		val result = readSse(port, path, openAiBody("fake-small:in=50,out=3", stream = true))

		assertEquals(HttpStatus.OK, result.status)
		assertTrue(MediaType.TEXT_EVENT_STREAM.isCompatibleWith(result.contentType), "content type was ${result.contentType}")
		assertNull(result.error)
		val frames = result.frames
		assertEquals(6, frames.size, frames.toString())
		frames.forEach { assertTrue(it.startsWith("data: "), it) }
		assertEquals("data: [DONE]", frames.last())

		val chunks = frames.dropLast(1).map { mapper.json(it) }
		chunks.forEach {
			assertEquals("chat.completion.chunk", it.path("object").stringValue())
			assertEquals("fake-small", it.path("model").stringValue())
			assertFalse(it.has("usage"), "usage key must be absent without include_usage")
		}
		assertEquals(1, chunks.map { it.path("id").stringValue() }.distinct().size)
		assertEquals("assistant", chunks[0].path("choices").path(0).path("delta").path("role").stringValue())
		assertEquals("", chunks[0].path("choices").path(0).path("delta").path("content").stringValue())
		for (i in 0 until 3) {
			val choice = chunks[1 + i].path("choices").path(0)
			assertEquals(FakeText.chunk(i), choice.path("delta").path("content").stringValue())
			assertTrue(choice.path("finish_reason").isNull)
		}
		val finish = chunks[4].path("choices").path(0)
		assertEquals(0, finish.path("delta").size())
		assertEquals("stop", finish.path("finish_reason").stringValue())
	}

	@Test
	fun `include_usage adds a usage chunk before DONE and null usage on the others`() {
		val result = readSse(port, path, openAiBody("fake-small:in=50,cached=10,out=3", stream = true, includeUsage = true))

		assertNull(result.error)
		val frames = result.frames
		assertEquals(7, frames.size, frames.toString())
		assertEquals("data: [DONE]", frames.last())
		val chunks = frames.dropLast(1).map { mapper.json(it) }
		chunks.dropLast(1).forEach {
			assertTrue(it.has("usage") && it.path("usage").isNull, "usage must be present and null: $it")
		}
		val usageChunk = chunks.last()
		assertEquals(0, usageChunk.path("choices").size())
		assertEquals(50, usageChunk.path("usage").path("prompt_tokens").intValue())
		assertEquals(3, usageChunk.path("usage").path("completion_tokens").intValue())
		assertEquals(53, usageChunk.path("usage").path("total_tokens").intValue())
		assertEquals(10, usageChunk.path("usage").path("prompt_tokens_details").path("cached_tokens").intValue())
	}

	@Test
	fun `err directive answers with that status in the OpenAI error shape`() {
		post(openAiBody("fake-small:err=429"))
			.expectStatus().isEqualTo(429)
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("rate_limit_error")
			.jsonPath("$.error.message").isEqualTo("Rate limit reached for requests")
			.jsonPath("$.error.param").isEmpty
			.jsonPath("$.error.code").isEmpty
		post(openAiBody("fake-small:err=503"))
			.expectStatus().isEqualTo(503)
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("service_unavailable_error")
			.jsonPath("$.error.code").isEqualTo("server_is_overloaded")
	}

	@Test
	fun `streamerr sends an error frame and ends without DONE`() {
		val result = readSse(port, path, openAiBody("fake-small:out=5,streamerr=2", stream = true))

		assertNull(result.error)
		val frames = result.frames
		assertEquals(4, frames.size, frames.toString())
		assertEquals(FakeText.chunk(1), mapper.json(frames[2]).path("choices").path(0).path("delta").path("content").stringValue())
		val error = mapper.json(frames[3]).path("error")
		assertEquals("server_error", error.path("type").stringValue())
		assertFalse(result.raw.contains("[DONE]"))
	}

	@Test
	fun `abort closes the connection after the given chunk with no terminating frame`() {
		val result = readSse(port, path, openAiBody("fake-small:out=5,abort=2", stream = true))

		assertEquals(HttpStatus.OK, result.status)
		assertNotNull(result.error, "the client must see the connection cut, got frames ${result.frames}")
		val frames = result.frames
		assertEquals(3, frames.size, frames.toString())
		assertEquals(FakeText.chunk(1), mapper.json(frames[2]).path("choices").path(0).path("delta").path("content").stringValue())
		assertFalse(result.raw.contains("finish_reason\":\"stop\""))
		assertFalse(result.raw.contains("[DONE]"))
	}

	@Test
	fun `a missing model is a 400 naming the parameter`() {
		post(mapOf("messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.param").isEqualTo("model")
		post(mapOf("model" to "fake-small", "messages" to emptyList<Any>()))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.param").isEqualTo("messages")
	}

	@Test
	fun `an unknown directive and malformed JSON are 400s in the OpenAI shape`() {
		post(openAiBody("fake-small:bogus=1"))
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
			.jsonPath("$.error.message").isEqualTo("Invalid model directive: unknown directive 'bogus'")
			.jsonPath("$.error.param").isEqualTo("model")
		post("{not json")
			.expectStatus().isBadRequest
			.expectBody()
			.jsonPath("$.error.type").isEqualTo("invalid_request_error")
	}
}
