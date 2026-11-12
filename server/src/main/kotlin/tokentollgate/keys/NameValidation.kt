package tokentollgate.keys

import tokentollgate.common.BadRequestException

private const val MAX_NAME_LENGTH = 100

/** Trims a project or key name and rejects it if blank or longer than [MAX_NAME_LENGTH]. */
internal fun validateName(name: String): String {
	val clean = name.trim()
	if (clean.isEmpty()) throw BadRequestException("name must not be blank")
	if (clean.length > MAX_NAME_LENGTH) throw BadRequestException("name must be at most $MAX_NAME_LENGTH characters")
	return clean
}
