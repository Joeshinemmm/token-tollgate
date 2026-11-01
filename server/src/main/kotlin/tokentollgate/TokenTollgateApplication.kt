package tokentollgate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TokenTollgateApplication

fun main(args: Array<String>) {
	runApplication<TokenTollgateApplication>(*args)
}
