package tokentollgate

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import

const val TEST_ADMIN_TOKEN = "test-admin-token-0123456789abcdef"

/**
 * A full application on a random port, backed by a Testcontainers PostgreSQL, with a known admin token.
 * Every integration test uses this so that they share one cached application context.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
	webEnvironment = WebEnvironment.RANDOM_PORT,
	properties = ["tollgate.admin.token=$TEST_ADMIN_TOKEN"],
)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
