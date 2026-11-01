package tokentollgate

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
	fromApplication<TokenTollgateApplication>().with(TestcontainersConfiguration::class).run(*args)
}
