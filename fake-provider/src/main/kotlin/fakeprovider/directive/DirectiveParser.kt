package fakeprovider.directive

import fakeprovider.FakeProperties
import org.springframework.stereotype.Component

/**
 * Parses `<model>[:<key>=<value>[,<key>=<value>...]]`. Keys: in, cached, out, ttft, gap, err, abort, streamerr.
 * Anything the request does not say comes from [FakeProperties.Defaults].
 */
@Component
class DirectiveParser(properties: FakeProperties) {

	private val defaults = properties.defaults

	fun parse(modelField: String): Directive {
		val model = modelField.substringBefore(DIRECTIVE_SEPARATOR).trim()
		if (model.isEmpty()) throw DirectiveException("model must not be blank")
		var directive = Directive(
			model = model,
			inputTokens = defaults.inputTokens,
			cachedInputTokens = defaults.cachedInputTokens,
			outputTokens = defaults.outputTokens,
			ttftMs = defaults.ttftMs,
			gapMs = defaults.chunkIntervalMs,
		)
		val spec = modelField.substringAfter(DIRECTIVE_SEPARATOR, missingDelimiterValue = "")
		for (item in spec.split(',')) {
			val part = item.trim()
			if (part.isEmpty()) continue
			val key = part.substringBefore('=').trim()
			val value = if (part.contains('=')) part.substringAfter('=').trim() else null
			directive = when (key) {
				"in" -> directive.copy(inputTokens = int(key, value))
				"cached" -> directive.copy(cachedInputTokens = int(key, value))
				"out" -> directive.copy(outputTokens = int(key, value, max = MAX_OUTPUT_TOKENS))
				"ttft" -> directive.copy(ttftMs = int(key, value).toLong())
				"gap" -> directive.copy(gapMs = int(key, value).toLong())
				"err" -> directive.copy(errorStatus = int(key, value))
				"abort" -> directive.copy(abortAfter = int(key, value))
				"streamerr" -> directive.copy(streamErrorAfter = if (value == null) 1 else int(key, value))
				else -> throw DirectiveException("unknown directive '$key'")
			}
		}
		return validated(directive)
	}

	private fun int(key: String, value: String?, max: Int = Int.MAX_VALUE): Int {
		if (value == null) throw DirectiveException("directive '$key' needs a value")
		val parsed = value.toIntOrNull() ?: throw DirectiveException("directive '$key' must be a non-negative integer")
		if (parsed < 0) throw DirectiveException("directive '$key' must be a non-negative integer")
		if (parsed > max) throw DirectiveException("directive '$key' must be at most $max")
		return parsed
	}

	private fun validated(directive: Directive): Directive {
		if (directive.cachedInputTokens > directive.inputTokens) throw DirectiveException("cached must not exceed in")
		directive.errorStatus?.let { if (it !in 400..599) throw DirectiveException("err must be an HTTP status between 400 and 599") }
		directive.abortAfter?.let { if (it < 1) throw DirectiveException("abort must be at least 1") }
		directive.streamErrorAfter?.let { if (it < 1) throw DirectiveException("streamerr must be at least 1") }
		return directive
	}

	companion object {
		const val DIRECTIVE_SEPARATOR = ':'
		const val MAX_OUTPUT_TOKENS = 200_000
	}
}
