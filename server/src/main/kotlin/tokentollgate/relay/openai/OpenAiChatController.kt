package tokentollgate.relay.openai

import kotlinx.coroutines.reactor.mono
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tokentollgate.relay.RelayEngine
import tokentollgate.security.AuthenticatedKey

/** OpenAI-compatible entry point; the gateway chain has already authenticated the virtual key. */
@RestController
class OpenAiChatController(private val engine: RelayEngine, private val adapter: OpenAiAdapter) {

	@PostMapping(PATH)
	fun chatCompletions(
		@AuthenticationPrincipal key: AuthenticatedKey,
		@RequestBody(required = false) body: ByteArray?,
		exchange: ServerWebExchange,
	): Mono<Void> = mono<Void> {
		engine.relay(adapter, exchange, key, body)
		null
	}

	companion object {
		const val PATH = "/v1/chat/completions"
	}
}
