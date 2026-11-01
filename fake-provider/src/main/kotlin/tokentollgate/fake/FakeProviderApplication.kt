package tokentollgate.fake

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FakeProviderApplication

fun main(args: Array<String>) {
	runApplication<FakeProviderApplication>(*args)
}
