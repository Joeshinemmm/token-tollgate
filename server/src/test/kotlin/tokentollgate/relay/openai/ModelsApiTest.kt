package tokentollgate.relay.openai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import tokentollgate.IntegrationTest
import tokentollgate.createProject
import tokentollgate.db.ModelPriceRepository
import tokentollgate.ensureFakePrices
import tokentollgate.issueKey
import tokentollgate.withKey
import kotlin.test.assertTrue

@IntegrationTest
class ModelsApiTest {

	@Autowired lateinit var client: WebTestClient
	@Autowired lateinit var prices: ModelPriceRepository

	@Test
	fun `models of enabled providers are listed in OpenAI shape`() = runBlocking<Unit> {
		prices.ensureFakePrices()
		val key = client.issueKey(client.createProject().id).key
		client.get().uri("/v1/models").withKey(key)
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.object").isEqualTo("list")
			.jsonPath("$.data[?(@.id == 'fake-relay')].owned_by").isEqualTo("fake")
			.jsonPath("$.data[?(@.id == 'fake-relay')].object").isEqualTo("model")
			.jsonPath("$.data[?(@.id == 'fake-large')].created").value<List<Number>> { assertTrue(it.single().toLong() > 0) }
	}

	@Test
	fun `the list needs a virtual key`() {
		client.get().uri("/v1/models")
			.exchange()
			.expectStatus().isUnauthorized
			.expectBody()
			.jsonPath("$.error.code").isEqualTo("invalid_api_key")
	}
}
