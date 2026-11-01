package tokentollgate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TokenTollgateApplication

fun main(args: Array<String>) {
	runApplication<TokenTollgateApplication>(*args)
}
