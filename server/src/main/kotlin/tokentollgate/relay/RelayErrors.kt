package tokentollgate.relay

import org.springframework.http.HttpStatus
import tokentollgate.upstream.Provider

/** An error the gateway itself raises, written in OpenAI's `{"error": {...}}` shape (the Anthropic shape is in [tokentollgate.relay.anthropic.AnthropicErrors]). */
data class GatewayError(
	val status: HttpStatus,
	val message: String,
	val type: String,
	val param: String? = null,
	val code: String? = null,
) {
	fun openAiBody(): Map<String, Any?> =
		mapOf("error" to linkedMapOf("message" to message, "type" to type, "param" to param, "code" to code))
}

object RelayErrors {

	fun badRequest(message: String, param: String?) =
		GatewayError(HttpStatus.BAD_REQUEST, message, "invalid_request_error", param)

	fun modelNotFound(model: String) = GatewayError(
		HttpStatus.NOT_FOUND, "The model '$model' does not exist or no provider is configured for it",
		"invalid_request_error", param = "model", code = "model_not_found",
	)

	fun providerDisabled(provider: Provider) = GatewayError(
		HttpStatus.SERVICE_UNAVAILABLE, "Provider '${provider.name}' is disabled: environment variable ${provider.apiKeyEnv} is empty",
		"server_error", code = "provider_disabled",
	)

	fun upstreamUnreachable(provider: Provider) = GatewayError(
		HttpStatus.BAD_GATEWAY, "Could not connect to provider '${provider.name}'", "server_error", code = "upstream_unreachable",
	)

	fun upstreamTimeout(provider: Provider) = GatewayError(
		HttpStatus.GATEWAY_TIMEOUT, "Provider '${provider.name}' did not respond in time", "server_error", code = "upstream_timeout",
	)

	fun streamInterrupted(provider: Provider) = GatewayError(
		HttpStatus.BAD_GATEWAY, "Provider '${provider.name}' ended the stream unexpectedly", "server_error", code = "stream_interrupted",
	)

	fun streamTimeout(provider: Provider) = GatewayError(
		HttpStatus.GATEWAY_TIMEOUT, "Provider '${provider.name}' stopped sending stream data", "server_error", code = "upstream_timeout",
	)

	fun gatewayFailure() =
		GatewayError(HttpStatus.INTERNAL_SERVER_ERROR, "The gateway failed to relay the request", "server_error", code = "gateway_error")
}
