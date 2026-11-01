package tokentollgate.admin

import tokentollgate.db.ModelPrice
import tokentollgate.db.Project
import tokentollgate.db.VirtualKey
import tokentollgate.keys.IssuedKey
import tokentollgate.pricing.PriceSpec
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateProjectRequest(val name: String)

data class UpdateProjectRequest(val name: String? = null, val archived: Boolean? = null)

data class ProjectResponse(
	val id: UUID,
	val name: String,
	val createdAt: Instant,
	val archivedAt: Instant?,
) {
	companion object {
		fun from(project: Project) = ProjectResponse(
			id = requireNotNull(project.id),
			name = project.name,
			createdAt = project.createdAt,
			archivedAt = project.archivedAt,
		)
	}
}

data class IssueKeyRequest(val name: String)

data class VirtualKeyResponse(
	val id: UUID,
	val projectId: UUID,
	val name: String,
	val keyPrefix: String,
	val createdAt: Instant,
	val revokedAt: Instant?,
	val lastUsedAt: Instant?,
) {
	companion object {
		fun from(key: VirtualKey) = VirtualKeyResponse(
			id = requireNotNull(key.id),
			projectId = key.projectId,
			name = key.name,
			keyPrefix = key.keyPrefix,
			createdAt = key.createdAt,
			revokedAt = key.revokedAt,
			lastUsedAt = key.lastUsedAt,
		)
	}
}

/** Returned only once, right after issuing. [key] is the plaintext the client must keep. */
data class IssuedKeyResponse(
	val id: UUID,
	val projectId: UUID,
	val name: String,
	val keyPrefix: String,
	val createdAt: Instant,
	val key: String,
) {
	companion object {
		fun from(issued: IssuedKey) = IssuedKeyResponse(
			id = requireNotNull(issued.key.id),
			projectId = issued.key.projectId,
			name = issued.key.name,
			keyPrefix = issued.key.keyPrefix,
			createdAt = issued.key.createdAt,
			key = issued.plaintext,
		)
	}
}

/** Rates are USD per million tokens. [effectiveFrom] is a date (`2026-11-01`, midnight UTC) or an ISO-8601 instant. */
data class UpsertPriceRequest(
	val provider: String? = null,
	val model: String? = null,
	val inputPerMtok: BigDecimal? = null,
	val outputPerMtok: BigDecimal? = null,
	val cachedInputPerMtok: BigDecimal? = null,
	val currency: String? = null,
	val effectiveFrom: String? = null,
) {
	fun toSpec(): PriceSpec = PriceSpec.of(
		provider = provider,
		model = model,
		inputPerMtok = inputPerMtok,
		outputPerMtok = outputPerMtok,
		cachedInputPerMtok = cachedInputPerMtok,
		currency = currency,
		effectiveFrom = effectiveFrom,
	)
}

data class PriceResponse(
	val id: UUID,
	val provider: String,
	val model: String,
	val inputPerMtok: BigDecimal,
	val outputPerMtok: BigDecimal,
	val cachedInputPerMtok: BigDecimal?,
	val currency: String,
	val effectiveFrom: Instant,
	val createdAt: Instant,
) {
	companion object {
		fun from(price: ModelPrice) = PriceResponse(
			id = requireNotNull(price.id),
			provider = price.provider,
			model = price.model,
			inputPerMtok = price.inputPerMtok,
			outputPerMtok = price.outputPerMtok,
			cachedInputPerMtok = price.cachedInputPerMtok,
			currency = price.currency,
			effectiveFrom = price.effectiveFrom,
			createdAt = price.createdAt,
		)
	}
}

data class AdminError(val error: AdminErrorBody)

data class AdminErrorBody(val code: String, val message: String)
