package fakeprovider

import fakeprovider.anthropic.AnthropicFakeController
import fakeprovider.openai.OpenAiFakeController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Only lower bounds are asserted: the delays must have happened, how much later the answer arrives is not fixed. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TimingTest {

	@Autowired
	lateinit var client: WebTestClient

	@LocalServerPort
	var port: Int = 0

	@Test
	fun `ttft delays a plain response`() {
		val started = System.nanoTime()
		client.postJson(OpenAiFakeController.PATH, openAiBody("fake-small:ttft=300,out=1"))
			.exchange()
			.expectStatus().isOk
		val elapsedMs = (System.nanoTime() - started) / 1_000_000
		assertTrue(elapsedMs >= 300, "expected at least 300 ms, took $elapsedMs ms")
	}

	@Test
	fun `gap spaces the content chunks of a stream`() {
		val started = System.nanoTime()
		val result = readSse(port, AnthropicFakeController.PATH, anthropicBody("fake-small:out=5,gap=50", stream = true))
		val elapsedMs = (System.nanoTime() - started) / 1_000_000
		assertNull(result.error)
		assertEquals(11, result.frames.size)
		assertTrue(elapsedMs >= 200, "four gaps of 50 ms expected, took $elapsedMs ms")
	}
}
