package tokentollgate.db

/** An enum stored as a text column whose values are fixed by a CHECK constraint in the schema. */
interface DbEnum {
	val dbValue: String
}

enum class Protocol(override val dbValue: String) : DbEnum {
	OPENAI("openai"),
	ANTHROPIC("anthropic"),
}

enum class UsageSource(override val dbValue: String) : DbEnum {
	RESPONSE("response"),
	STREAM("stream"),
	NONE("none"),
}

enum class Outcome(override val dbValue: String) : DbEnum {
	OK("ok"),
	UPSTREAM_ERROR("upstream_error"),
	CLIENT_ABORT("client_abort"),
	TIMEOUT("timeout"),
	GATEWAY_ERROR("gateway_error"),
}
