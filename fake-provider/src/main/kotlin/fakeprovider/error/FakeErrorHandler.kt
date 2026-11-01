package fakeprovider.error

import fakeprovider.common.Ids
import fakeprovider.directive.DirectiveException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

/** Answers malformed requests with a 400 body in the shape of the protocol the client called. */
@RestControllerAdvice
class FakeErrorHandler {

	@ExceptionHandler(DirectiveException::class)
	fun directive(ex: DirectiveException, exchange: ServerWebExchange) =
		badRequest(exchange, "Invalid model directive: ${ex.message}", param = "model")

	@ExceptionHandler(InvalidRequestException::class)
	fun invalidRequest(ex: InvalidRequestException, exchange: ServerWebExchange) =
		badRequest(exchange, ex.message ?: "Invalid request", ex.param)

	@ExceptionHandler(ServerWebInputException::class)
	fun invalidInput(ex: ServerWebInputException, exchange: ServerWebExchange) =
		badRequest(exchange, "Request body must be a JSON object", param = null)

	private fun badRequest(exchange: ServerWebExchange, message: String, param: String?): ResponseEntity<Map<String, Any?>> {
		val protocol = Protocol.of(exchange.request.path.value())
		val requestId = Ids.request()
		val response = ResponseEntity.status(HttpStatus.BAD_REQUEST)
		if (protocol == Protocol.ANTHROPIC) response.header(REQUEST_ID_HEADER, requestId)
		return response.body(FakeErrors.body(protocol, HttpStatus.BAD_REQUEST.value(), message, param, requestId))
	}

	companion object {
		const val REQUEST_ID_HEADER = "request-id"
	}
}
