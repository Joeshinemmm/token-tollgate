package tokentollgate.keys

import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import tokentollgate.common.BadRequestException
import tokentollgate.common.ConflictException
import tokentollgate.common.NotFoundException
import tokentollgate.db.Project
import tokentollgate.db.ProjectRepository
import tokentollgate.db.dbNow
import java.util.UUID

@Service
class ProjectService(private val projects: ProjectRepository) {

	suspend fun create(name: String): Project {
		val cleanName = validateName(name)
		if (projects.findByName(cleanName) != null) {
			throw ConflictException("Project name already exists: $cleanName")
		}
		return projects.save(Project(name = cleanName))
	}

	suspend fun list(includeArchived: Boolean): List<Project> =
		if (includeArchived) projects.findAllByOrderByCreatedAtAsc().toList()
		else projects.findAllByArchivedAtIsNullOrderByCreatedAtAsc().toList()

	suspend fun get(id: UUID): Project =
		projects.findById(id) ?: throw NotFoundException("Project not found: $id")

	suspend fun update(id: UUID, name: String?, archived: Boolean?): Project {
		var project = get(id)
		if (name != null) {
			val cleanName = validateName(name)
			val existing = projects.findByName(cleanName)
			if (existing != null && existing.id != id) {
				throw ConflictException("Project name already exists: $cleanName")
			}
			project = project.copy(name = cleanName)
		}
		if (archived != null) {
			project = when {
				archived && project.archivedAt == null -> project.copy(archivedAt = dbNow())
				!archived && project.archivedAt != null -> project.copy(archivedAt = null)
				else -> project
			}
		}
		return projects.save(project)
	}

	private fun validateName(name: String): String {
		val clean = name.trim()
		if (clean.isEmpty()) throw BadRequestException("name must not be blank")
		if (clean.length > MAX_NAME_LENGTH) throw BadRequestException("name must be at most $MAX_NAME_LENGTH characters")
		return clean
	}

	companion object {
		const val MAX_NAME_LENGTH = 100
	}
}
