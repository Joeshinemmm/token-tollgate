package tokentollgate.db

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface ProjectRepository : CoroutineCrudRepository<Project, UUID>

interface VirtualKeyRepository : CoroutineCrudRepository<VirtualKey, UUID> {
	suspend fun findByKeyHash(keyHash: String): VirtualKey?
}

interface ModelPriceRepository : CoroutineCrudRepository<ModelPrice, UUID>

interface UsageRecordRepository : CoroutineCrudRepository<UsageRecord, Long>
