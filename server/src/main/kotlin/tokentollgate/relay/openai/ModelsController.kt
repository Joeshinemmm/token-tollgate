package tokentollgate.relay.openai

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tokentollgate.pricing.PricingService
import tokentollgate.upstream.ProviderRegistry

/**
 * `GET /v1/models` in OpenAI's list shape: the models of enabled providers that the price table knows
 * (latest row per model, `created` = its effective date) plus the exact model names from configuration.
 */
@RestController
class ModelsController(private val registry: ProviderRegistry, private val pricing: PricingService) {

	@GetMapping("/v1/models")
	suspend fun list(): Map<String, Any> {
		val enabled = registry.enabledProviders().associateBy { it.name }
		val models = linkedMapOf<String, Map<String, Any>>()
		for (price in pricing.list()) {
			if (price.provider !in enabled) continue
			models.putIfAbsent(price.model, model(price.model, created = price.effectiveFrom.epochSecond, ownedBy = price.provider))
		}
		for (provider in enabled.values) {
			for (name in provider.explicitModels) models.putIfAbsent(name, model(name, created = 0, ownedBy = provider.name))
		}
		return mapOf("object" to "list", "data" to models.values.sortedBy { it["id"] as String })
	}

	private fun model(id: String, created: Long, ownedBy: String): Map<String, Any> =
		linkedMapOf("id" to id, "object" to "model", "created" to created, "owned_by" to ownedBy)
}
