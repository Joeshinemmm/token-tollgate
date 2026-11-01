package fakeprovider

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FakeProviderApplication

/** Settings live in fake-provider.yml so that this module never shadows the gateway's application.yml on a shared classpath. */
fun main(args: Array<String>) {
	runApplication<FakeProviderApplication>(*args) {
		setDefaultProperties(mapOf("spring.config.name" to "fake-provider"))
	}
}
