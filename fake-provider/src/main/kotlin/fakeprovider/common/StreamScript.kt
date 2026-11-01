package fakeprovider.common

import fakeprovider.directive.Directive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Raised inside a stream so that the connection closes without any terminating frame. */
class StreamAbortedException : IOException("stream aborted by directive")

/**
 * The frames of one streamed response in order: [head] after the ttft delay, [contentFrame] for each of
 * [outputTokens] with the gap delay between them, then [tail]. `abort` ends the stream with an error signal
 * and `streamerr` substitutes [errorFrame] for the rest of the stream.
 */
fun streamFrames(
	directive: Directive,
	outputTokens: Int,
	head: List<String>,
	contentFrame: (index: Int) -> String,
	tail: List<String>,
	errorFrame: String,
): Flow<String> = flow {
	if (directive.ttftMs > 0) delay(directive.ttftMs)
	head.forEach { emit(it) }
	for (index in 0 until outputTokens) {
		if (index > 0 && directive.gapMs > 0) delay(directive.gapMs)
		emit(contentFrame(index))
		val sent = index + 1
		if (directive.abortAfter == sent) throw StreamAbortedException()
		if (directive.streamErrorAfter == sent) {
			emit(errorFrame)
			return@flow
		}
	}
	tail.forEach { emit(it) }
}
