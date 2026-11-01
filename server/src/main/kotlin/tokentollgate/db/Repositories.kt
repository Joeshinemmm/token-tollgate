package tokentollgate.db

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

interface ProjectRepository : CoroutineCrudRepository<Project, UUID> {
	suspend fun findByName(name: String): Project?
	fun findAllByOrderByCreatedAtAsc(): Flow<Project>
	fun findAllByArchivedAtIsNullOrderByCreatedAtAsc(): Flow<Project>
}

/** The columns needed to accept or reject a presented key, read with a single query. */
data class KeyAuthView(
	val keyId: UUID,
	val projectId: UUID,
	val keyPrefix: String,
	val revokedAt: Instant?,
	val projectArchivedAt: Instant?,
)

interface VirtualKeyRepository : CoroutineCrudRepository<VirtualKey, UUID> {
	suspend fun findByKeyHash(keyHash: String): VirtualKey?
	fun findAllByOrderByCreatedAtAsc(): Flow<VirtualKey>
	fun findAllByProjectIdOrderByCreatedAtAsc(projectId: UUID): Flow<VirtualKey>

	@Query(
		"""
		SELECT k.id AS key_id, k.project_id, k.key_prefix, k.revoked_at, p.archived_at AS project_archived_at
		FROM virtual_keys k
		JOIN projects p ON p.id = k.project_id
		WHERE k.key_hash = :keyHash
		""",
	)
	suspend fun findAuthViewByKeyHash(keyHash: String): KeyAuthView?

	@Modifying
	@Query("UPDATE virtual_keys SET last_used_at = :usedAt WHERE id = :id")
	suspend fun updateLastUsedAt(id: UUID, usedAt: Instant): Int
}

interface ModelPriceRepository : CoroutineCrudRepository<ModelPrice, UUID> {
	suspend fun findByProviderAndModelAndEffectiveFrom(provider: String, model: String, effectiveFrom: Instant): ModelPrice?
	fun findAllByOrderByProviderAscModelAscEffectiveFromDesc(): Flow<ModelPrice>

	/** The row in force at [at]: the latest `effective_from` that is not after it. Served by the unique index. */
	@Query(
		"""
		SELECT * FROM model_prices
		WHERE provider = :provider AND model = :model AND effective_from <= :at
		ORDER BY effective_from DESC
		LIMIT 1
		""",
	)
	suspend fun findEffective(provider: String, model: String, at: Instant): ModelPrice?
}

interface UsageRecordRepository : CoroutineCrudRepository<UsageRecord, Long>
