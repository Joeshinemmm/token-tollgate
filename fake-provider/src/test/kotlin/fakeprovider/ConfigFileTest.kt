package fakeprovider

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** fake-provider.yml is only read under that config name; the FAKE_* placeholders must reach FakeProperties. */
@SpringBootTest(properties = ["spring.config.name=fake-provider", "FAKE_OUTPUT_TOKENS=7", "FAKE_TTFT_MS=12", "FAKE_API_KEY="])
class ConfigFileTest {

	@Autowired
	lateinit var properties: FakeProperties

	@Test
	fun `environment variables reach the defaults through fake-provider yml`() {
		assertEquals(7, properties.defaults.outputTokens)
		assertEquals(12, properties.defaults.ttftMs)
		assertEquals(100, properties.defaults.inputTokens)
		assertNull(properties.requiredApiKey())
	}
}
