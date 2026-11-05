package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.core.memory.MemoryRanker
import com.example.persona.data.local.dao.MemoryDao
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.repository.MemoryRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Singleton
class RoomMemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val authManager: AuthManager
) : MemoryRepository {
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    internal fun notifyDerivedChange() {
        _changes.update { it + 1 }
    }

    override fun observe(personaId: String): Flow<List<Memory>> {
        require(personaId.isNotBlank())
        val identity = authManager.identity.value
        return channelFlow {
            authManager.withIdentity(identity) {
                memoryDao.observe(identity.ownerId, personaId, System.currentTimeMillis()).collect { rows ->
                    val now = System.currentTimeMillis()
                    send(rows.filter { it.isVisible(identity.ownerId, personaId, now) }.map { it.toDomain() })
                }
            }
        }
    }

    override suspend fun save(
        personaId: String?, type: MemoryType, content: String, cloudAllowed: Boolean,
        sourceMessageId: String?, id: String?, expectedRevision: Int?, expiresAt: Long?
    ): Memory = authManager.withIdentity {
        require(personaId == null || personaId.isNotBlank())
        require(content.isNotBlank() && content.length <= 400) { "Memory content must contain 1..400 characters" }
        require((id == null) == (expectedRevision == null)) { "Updates require an ID and expected revision" }
        require(id == null || id.isNotBlank())
        require(expectedRevision == null || expectedRevision in 1 until Int.MAX_VALUE)
        withContext(NonCancellable) {
            authManager.withLocalIdentity { owner ->
                saveWithinIdentity(
                    owner, personaId, type, content, cloudAllowed, sourceMessageId,
                    id, expectedRevision, expiresAt
                )
            }
        }
    }

    internal suspend fun saveWithinIdentity(
        owner: String, personaId: String?, type: MemoryType, content: String,
        cloudAllowed: Boolean, sourceMessageId: String?, id: String?,
        expectedRevision: Int?, expiresAt: Long?
    ): Memory {
        check(authManager.requestIdentity().ownerId == owner) { "Memory owner changed" }
        if (id?.startsWith("summary-") == true) {
            val original = memoryDao.findOwned(id, owner)
            require(original != null) { "Summary is unavailable" }
            require(!cloudAllowed && personaId == original.personaId &&
                sourceMessageId == original.sourceMessageId) {
                "Summary scope, sources and local-only permission cannot be changed"
            }
        }
        val now = System.currentTimeMillis()
        val saved = memoryDao.saveMemory(
            MemoryEntity(
                id = id ?: UUID.randomUUID().toString(), ownerId = owner, personaId = personaId,
                type = type.name, content = content, sourceMessageId = sourceMessageId,
                createdAt = now, updatedAt = now, expiresAt = expiresAt, cloudAllowed = cloudAllowed
            ),
            expectedRevision
        )
        notifyDerivedChange()
        return saved.toDomain()
    }

    override suspend fun delete(id: String, expectedRevision: Int) = authManager.withIdentity {
        require(id.isNotBlank() && expectedRevision in 1 until Int.MAX_VALUE)
        withContext(NonCancellable) {
            authManager.withLocalIdentity { owner ->
                check(memoryDao.deleteMemory(id, owner, expectedRevision, System.currentTimeMillis()) == 1) {
                    "Memory is unavailable or its revision changed"
                }
                _changes.update { it + 1 }
            }
        }
    }

    override suspend fun deleteForPersona(personaId: String) = authManager.withIdentity {
        require(personaId.isNotBlank())
        withContext(NonCancellable) {
            authManager.withLocalIdentity { owner ->
                if (memoryDao.deletePersonaMemories(owner, personaId, System.currentTimeMillis()) > 0) {
                    _changes.update { it + 1 }
                }
            }
        }
    }

    override suspend fun selectForPrompt(personaId: String, cloud: Boolean, query: String): List<Memory> =
        authManager.withIdentity {
            require(personaId.isNotBlank())
            val owner = authManager.requestIdentity().ownerId
            val rows = memoryDao.selectForPrompt(owner, personaId, cloud, System.currentTimeMillis())
            val now = System.currentTimeMillis()
            var characters = 0
            val visible = rows.asSequence()
                .filter { it.isVisible(owner, personaId, now) && (!cloud || it.cloudAllowed) }
                .filter { it.content.length in 1..400 && it.content.isNotBlank() }
            val candidates = if (query.isBlank()) visible.take(4) else visible
            val memories = candidates
                .map { it.toDomain() }
                .toList()
            MemoryRanker.rank(memories, query).asSequence()
                .take(4)
                .filter { memory ->
                    (characters + memory.content.length <= 1200).also { accepted ->
                        if (accepted) characters += memory.content.length
                    }
                }
                .toList()
                .filter { selected ->
                    if (query.isBlank()) true else {
                        val current = memoryDao.findOwned(selected.id, owner)
                        current != null && current.isVisible(owner, personaId, System.currentTimeMillis()) &&
                            (!cloud || current.cloudAllowed) && current.toDomain() == selected
                    }
                }
        }

    private fun MemoryEntity.isVisible(owner: String, persona: String, now: Long): Boolean =
        ownerId == owner && (personaId == null || personaId == persona) && !deleted &&
            (expiresAt == null || expiresAt > now)

    private fun MemoryEntity.toDomain() = Memory(
        id, ownerId, personaId, MemoryType.valueOf(type), content, sourceMessageId,
        createdAt, updatedAt, expiresAt, cloudAllowed, revision, deleted
    )
}
