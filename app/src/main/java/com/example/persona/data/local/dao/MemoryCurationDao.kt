package com.example.persona.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.persona.data.local.entity.MemoryCandidateEntity
import com.example.persona.data.local.entity.MemoryPreferencesEntity
import com.example.persona.data.local.entity.MemorySummarySourceEntity
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryCurationDao {
    @Query("SELECT * FROM memory_candidates WHERE ownerId = :owner AND personaId = :persona AND status = 'PENDING' ORDER BY createdAt, id")
    fun observe(owner: String, persona: String): Flow<List<MemoryCandidateEntity>>

    @Query("SELECT * FROM memory_candidates WHERE ownerId = :owner AND id = :id")
    suspend fun candidate(owner: String, id: String): MemoryCandidateEntity?

    @Query("UPDATE memory_candidates SET status = 'FAILED', content = '' WHERE ownerId = :owner AND status = 'RUNNING' AND id NOT IN (:activeIds)")
    suspend fun recoverTasks(owner: String, activeIds: List<String>)

    @Query("SELECT * FROM memory_candidates WHERE ownerId = :owner AND id = :id")
    fun observeTask(owner: String, id: String): Flow<MemoryCandidateEntity?>

    @Query("SELECT * FROM memory_preferences WHERE ownerId = :owner")
    suspend fun preferences(owner: String): MemoryPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun configure(preferences: MemoryPreferencesEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(candidate: MemoryCandidateEntity): Long

    @Query("UPDATE memory_candidates SET status = :status, content = '', memoryId = :memoryId WHERE ownerId = :owner AND id = :id AND status = 'PENDING'")
    suspend fun finish(owner: String, id: String, status: String, memoryId: String? = null): Int

    @Query("UPDATE memory_candidates SET status = 'PENDING', content = :content, memoryId = NULL, manual = 1 WHERE ownerId = :owner AND id = :id AND status IN ('IGNORED', 'INVALID')")
    suspend fun reopen(owner: String, id: String, content: String): Int

    @Query("UPDATE memory_candidates SET memoryId = :memoryId WHERE ownerId = :owner AND id = :id AND status = 'ACCEPTED'")
    suspend fun bindAcceptedMemory(owner: String, id: String, memoryId: String)

    @Query("UPDATE memory_candidates SET status = 'INVALID', content = '' WHERE ownerId = :owner AND status IN ('PENDING', 'RUNNING')")
    suspend fun invalidatePending(owner: String)

    @Query("UPDATE memory_candidates SET status = 'INVALID', content = '' WHERE ownerId = :owner AND sourceMessageId = :sourceId AND status = 'RUNNING'")
    suspend fun invalidateTasksForSource(owner: String, sourceId: String)

    @Query("UPDATE memory_candidates SET status = :status, content = '' WHERE ownerId = :owner AND id = :id AND status = 'RUNNING'")
    suspend fun finishTask(owner: String, id: String, status: String): Int

    @Query("SELECT * FROM memory_preferences WHERE ownerId = :owner")
    fun observePreferences(owner: String): Flow<MemoryPreferencesEntity?>

    @Query("SELECT * FROM messages WHERE ownerId = :owner AND id = :id")
    fun observeSource(owner: String, id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM memories WHERE ownerId = :owner AND (personaId = :persona OR personaId IS NULL) AND deleted = 0 AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC, id")
    suspend fun memories(owner: String, persona: String, now: Long): List<MemoryEntity>

    @Query("SELECT * FROM messages WHERE ownerId = :owner AND personaId = :persona AND isFromUser = 1 AND status = 'NORMAL' AND excludeFromHistory = 0 ORDER BY timestamp DESC, id LIMIT 24")
    suspend fun summaryMessages(owner: String, persona: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addSummarySources(sources: List<MemorySummarySourceEntity>)

    @Query("SELECT * FROM memory_summary_sources WHERE ownerId = :owner AND memoryId = :id ORDER BY sourceMessageId")
    suspend fun summarySources(owner: String, id: String): List<MemorySummarySourceEntity>
}
