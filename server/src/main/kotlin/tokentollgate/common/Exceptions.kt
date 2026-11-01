package tokentollgate.common

/** A request that is well-formed but refers to something that does not exist. Maps to HTTP 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** A request that conflicts with current state, such as a duplicate name. Maps to HTTP 409. */
class ConflictException(message: String) : RuntimeException(message)

/** A request whose input fails validation. Maps to HTTP 400. */
class BadRequestException(message: String) : RuntimeException(message)
