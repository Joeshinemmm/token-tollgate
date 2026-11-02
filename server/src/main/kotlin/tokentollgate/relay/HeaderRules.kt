package tokentollgate.relay

import org.springframework.http.HttpHeaders

/**
 * Which headers cross the gateway. Everything passes except credentials, the gateway's own header,
 * hop-by-hop headers, and `Accept-Encoding` (a compressed upstream stream could not be read on the way through).
 */
object HeaderRules {

	const val REQUEST_ID_HEADER = "x-tollgate-request-id"

	private val requestDrop = setOf(
		"authorization", "x-api-key", "x-tollgate-tag",
		"host", "content-length", "transfer-encoding", "connection", "expect", "accept-encoding",
	)

	private val responseDrop = setOf("content-length", "transfer-encoding", "connection", "content-encoding")

	fun copyRequestHeaders(from: HttpHeaders, to: HttpHeaders) = copy(from, to, requestDrop)

	fun copyResponseHeaders(from: HttpHeaders, to: HttpHeaders) = copy(from, to, responseDrop)

	private fun copy(from: HttpHeaders, to: HttpHeaders, drop: Set<String>) {
		from.forEach { name, values ->
			if (name.lowercase() !in drop) to.put(name, values.toList())
		}
	}
}
