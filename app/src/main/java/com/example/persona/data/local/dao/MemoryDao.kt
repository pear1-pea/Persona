package com.example.persona.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.persona.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MemoryDao {
    @Query("""
        SELECT * FROM memories
        WHERE ownerId = :ownerId AND (personaId = :personaId OR personaId IS NULL)
          AND deleted = 0 AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY updatedAt DESC, id ASC
    """)
    abstract fun observe(ownerId: String, personaId: String, now: Long): Flow<List<MemoryEntity>>

    @Query("""
        SELECT * FROM memories
        WHERE ownerId = :ownerId AND (personaId = :personaId OR personaId IS NULL)
          AND deleted = 0 AND (expiresAt IS NULL OR expiresAt > :now)
          AND (:cloud = 0 OR cloudAllowed = 1)
        ORDER BY updatedAt DESC, id ASC
    """)
    abstract suspend fun selectForPrompt(
        ownerId: String, personaId: String, cloud: Boolean, now: Long
    ): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id AND ownerId = :ownerId")
    abstract suspend fun findOwned(id: String, ownerId: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(memory: MemoryEntity)

    @Query("""
        UPDATE memories SET personaId = :personaId, type = :type, content = :content,
            sourceMessageId = :sourceMessageId, updatedAt = :now, expiresAt = :expiresAt,
            cloudAllowed = :cloudAllowed, revision = revision + 1
        WHERE id = :id AND ownerId = :ownerId AND revision = :expectedRevision
          AND deleted = 0 AND revision < 2147483647
    """)
    abstract suspend fun updateIfRevision(
        id: String, ownerId: String, expectedRevision: Int, personaId: String?,
        type: String, content: String, sourceMessageId: String?, now: Long,
        expiresAt: Long?, cloudAllowed: Boolean
    ): Int

    @Query("""
        UPDATE memories SET content = '', sourceMessageId = NULL, cloudAllowed = 0,
            deleted = 1, revision = revision + 1, updatedAt = :now
        WHERE id = :id AND ownerId = :ownerId AND revision = :expectedRevision
          AND deleted = 0 AND revision < 2147483647
    """)
    abstract suspend fun tombstoneIfRevision(
        id: String, ownerId: String, expectedRevision: Int, now: Long
    ): Int

    @Query("""
        SELECT personaId FROM messages
        WHERE id = :id AND ownerId = :ownerId
          AND isFromUser = 1 AND status = 'NORMAL'
    """)
    abstract suspend fun sourcePersonaId(id: String, ownerId: String): String?

    @Query("""
        UPDATE messages SET excludeFromHistory = 1
        WHERE id = :id AND ownerId = :ownerId AND personaId = :personaId
          AND isFromUser = 1 AND status = 'NORMAL'
    """)
    abstract suspend fun excludeSourceFromHistory(id: String, ownerId: String, personaId: String): Int

    @Query("""
        UPDATE messages SET excludeFromHistory = 1
        WHERE ownerId = :ownerId AND (:personaId IS NULL OR personaId = :personaId)
    """)
    abstract suspend fun excludeScopeFromHistory(ownerId: String, personaId: String?)

    @Query("""
        UPDATE memories SET content = '', sourceMessageId = NULL, cloudAllowed = 0,
            deleted = 1, revision = revision + 1, updatedAt = :now
        WHERE ownerId = :ownerId AND personaId = :personaId AND deleted = 0
    """)
    abstract suspend fun tombstoneByPersona(ownerId: String, personaId: String, now: Long): Int

    @Query("DELETE FROM memories WHERE ownerId = :ownerId")
    abstract suspend fun deleteByOwner(ownerId: String)

    @Query("DELETE FROM memories WHERE ownerId = :ownerId AND personaId = :personaId")
    abstract suspend fun deleteByPersona(ownerId: String, personaId: String)

    @Transaction
    open suspend fun saveMemory(memory: MemoryEntity, expectedRevision: Int?): MemoryEntity {
        require(memory.content.isNotBlank() && memory.content.length <= 400)
        val original = if (expectedRevision != null) findOwned(memory.id, memory.ownerId) else null
        val sourcePersona = memory.sourceMessageId?.let { sourceId ->
            val originalPersona = sourcePersonaId(sourceId, memory.ownerId)
            require(originalPersona != null) { "Source message is unavailable or not a stable user message" }
            require(memory.personaId == null || memory.personaId == originalPersona) {
                "Source message belongs to another persona"
            }
            originalPersona
        }
        if (expectedRevision == null) {
            insert(memory)
        } else {
            check(updateIfRevision(
                memory.id, memory.ownerId, expectedRevision, memory.personaId, memory.type,
                memory.content, memory.sourceMessageId, memory.updatedAt, memory.expiresAt,
                memory.cloudAllowed
            ) == 1) { "Memory is unavailable or its revision changed" }
        }
        if (sourcePersona != null) {
            check(excludeSourceFromHistory(
                requireNotNull(memory.sourceMessageId), memory.ownerId, sourcePersona
            ) == 1) { "Source message changed" }
        }
        excludeScopeFromHistory(memory.ownerId, memory.personaId)
        if (original != null && original.personaId != memory.personaId) {
            excludeScopeFromHistory(memory.ownerId, original.personaId)
        }
        return checkNotNull(findOwned(memory.id, memory.ownerId))
    }

    @Transaction
    open suspend fun deleteMemory(id: String, ownerId: String, expectedRevision: Int, now: Long): Int {
        val original = findOwned(id, ownerId) ?: return 0
        if (tombstoneIfRevision(id, ownerId, expectedRevision, now) == 0) return 0
        excludeScopeFromHistory(ownerId, original.personaId)
        return 1
    }

    @Transaction
    open suspend fun deletePersonaMemories(ownerId: String, personaId: String, now: Long): Int {
        val count = tombstoneByPersona(ownerId, personaId, now)
        if (count > 0) excludeScopeFromHistory(ownerId, personaId)
        return count
    }
}
