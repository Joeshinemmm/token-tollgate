package tokentollgate.admin

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tokentollgate.pricing.PricingService
import java.util.UUID

@RestController
@RequestMapping("/admin/v1/prices")
class PriceController(private val pricing: PricingService) {

	@GetMapping
	suspend fun list(
		@RequestParam(required = false) provider: String?,
		@RequestParam(required = false) model: String?,
	): List<PriceResponse> = pricing.list(provider, model).map(PriceResponse::from)

	/** Upsert keyed by (provider, model, effectiveFrom): 201 when the row is new, 200 when its rates were replaced. */
	@PutMapping
	suspend fun upsert(@RequestBody body: UpsertPriceRequest): ResponseEntity<PriceResponse> {
		val result = pricing.upsert(body.toSpec())
		val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
		return ResponseEntity.status(status).body(PriceResponse.from(result.price))
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	suspend fun delete(@PathVariable id: UUID) {
		pricing.delete(id)
	}
}
