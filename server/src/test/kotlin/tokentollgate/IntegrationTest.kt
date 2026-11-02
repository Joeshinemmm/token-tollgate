package tokentollgate

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration

const val TEST_ADMIN_TOKEN = "test-admin-token-0123456789abcdef"

/**
 * A full application backed by a Testcontainers PostgreSQL, with a known admin token and the fake provider
 * mounted under `/fake` on the same port (chosen up front by [FakeUpstreamInitializer]).
 * Every integration test uses this so that they share one cached application context.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
	webEnvironment = WebEnvironment.DEFINED_PORT,
	properties = ["tollgate.admin.token=$TEST_ADMIN_TOKEN"],
)
@ContextConfiguration(initializers = [FakeUpstreamInitializer::class])
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration::class, FakeUpstreamConfiguration::class)
annotation class IntegrationTest
