package tokentollgate.keys

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tokentollgate.common.BadRequestException
import tokentollgate.common.ConflictException
import tokentollgate.common.NotFoundException
import tokentollgate.db.KeyAuthView
import tokentollgate.db.VirtualKey
import tokentollgate.db.VirtualKeyRepository
import tokentollgate.db.dbNow
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A freshly issued key. [plaintext] is returned to the caller once and never stored. */
data class IssuedKey(val key: VirtualKey, val plaintext: String)

/** Why a presented key was rejected. The message never includes the key itself. */
sealed class KeyRejection(val message: String) {
	object Unknown : KeyRejection("Invalid API key")
	object Revoked : KeyRejection("API key has been revoked")
	object ProjectArchived : KeyRejection("Project is archived")
}

sealed class KeyAuthResult {
	data class Accepted(val view: KeyAuthView) : KeyAuthResult()
	data class Rejected(val reason: KeyRejection) : KeyAuthResult()
}

@Service
class VirtualKeyService(
	private val keys: VirtualKeyRepository,
	private val projects: ProjectService,
) {
	private val log = LoggerFactory.getLogger(VirtualKeyService::class.java)
	private val lastUseWritten = ConcurrentHashMap<UUID, Instant>()

	suspend fun issue(projectId: UUID, name: String): IssuedKey {
		val project = projects.get(projectId)
		if (project.archivedAt != null) throw ConflictException("Project is archived: $projectId")
		val cleanName = name.trim()
		if (cleanName.isEmpty()) throw BadRequestException("name must not be blank")
		if (cleanName.length > MAX_NAME_LENGTH) throw BadRequestException("name must be at most $MAX_NAME_LENGTH characters")

		val plaintext = KeyGenerator.generate()
		val saved = keys.save(
			VirtualKey(
				projectId = projectId,
				name = cleanName,
				keyPrefix = KeyGenerator.displayPrefix(plaintext),
				keyHash = KeyGenerator.hash(plaintext),
			),
		)
		return IssuedKey(saved, plaintext)
	}

	suspend fun list(projectId: UUID?): List<VirtualKey> =
		if (projectId == null) keys.findAllByOrderByCreatedAtAsc().toList()
		else keys.findAllByProjectIdOrderByCreatedAtAsc(projectId).toList()

	/** Revoking an already revoked key is a no-op, so the operation is idempotent. */
	suspend fun revoke(id: UUID): VirtualKey {
		val key = keys.findById(id) ?: throw NotFoundException("Key not found: $id")
		if (key.revokedAt != null) return key
		return keys.save(key.copy(revokedAt = dbNow()))
	}

	suspend fun authenticate(plaintext: String): KeyAuthResult {
		val view = keys.findAuthViewByKeyHash(KeyGenerator.hash(plaintext))
			?: return KeyAuthResult.Rejected(KeyRejection.Unknown)
		return when {
			view.revokedAt != null -> KeyAuthResult.Rejected(KeyRejection.Revoked)
			view.projectArchivedAt != null -> KeyAuthResult.Rejected(KeyRejection.ProjectArchived)
			else -> KeyAuthResult.Accepted(view)
		}
	}

	/**
	 * Updates `last_used_at` at most once per [LAST_USE_WRITE_INTERVAL] per key, without blocking the request.
	 * A failed update is logged and otherwise ignored.
	 */
	fun recordUse(keyId: UUID) {
		val now = Instant.now()
		var shouldWrite = false
		lastUseWritten.compute(keyId) { _, previous ->
			if (previous == null || Duration.between(previous, now) >= LAST_USE_WRITE_INTERVAL) {
				shouldWrite = true
				now
			} else {
				previous
			}
		}
		if (shouldWrite) {
			mono { keys.updateLastUsedAt(keyId, dbNow()) }
				.subscribe({}, { error -> log.warn("Failed to update last_used_at for key {}", keyId, error) })
		}
	}

	companion object {
		const val MAX_NAME_LENGTH = 100
		val LAST_USE_WRITE_INTERVAL: Duration = Duration.ofSeconds(60)
	}
}
