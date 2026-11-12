package tokentollgate.admin

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException
import tokentollgate.common.BadRequestException
import tokentollgate.common.ConflictException
import tokentollgate.common.NotFoundException

/** Maps admin API failures to `{"error":{"code":...,"message":...}}`. Security failures are handled by the entry point. */
@RestControllerAdvice(basePackageClasses = [ProjectController::class])
class AdminErrorHandler {

	@ExceptionHandler(NotFoundException::class)
	fun notFound(ex: NotFoundException) = error(HttpStatus.NOT_FOUND, "not_found", ex.message)

	@ExceptionHandler(ConflictException::class)
	fun conflict(ex: ConflictException) = error(HttpStatus.CONFLICT, "conflict", ex.message ?: CONFLICT_FALLBACK_MESSAGE)

	@ExceptionHandler(DataIntegrityViolationException::class)
	fun dataIntegrityViolation(ex: DataIntegrityViolationException) =
		error(HttpStatus.CONFLICT, "conflict", CONFLICT_FALLBACK_MESSAGE)

	@ExceptionHandler(BadRequestException::class)
	fun badRequest(ex: BadRequestException) = error(HttpStatus.BAD_REQUEST, "bad_request", ex.message)

	@ExceptionHandler(ServerWebInputException::class)
	fun invalidInput(ex: ServerWebInputException) = error(HttpStatus.BAD_REQUEST, "bad_request", ex.reason ?: "Invalid request")

	private fun error(status: HttpStatus, code: String, message: String?): ResponseEntity<AdminError> =
		ResponseEntity.status(status).body(AdminError(AdminErrorBody(code, message ?: status.reasonPhrase)))

	private companion object {
		const val CONFLICT_FALLBACK_MESSAGE = "Conflicts with existing data"
	}
}
