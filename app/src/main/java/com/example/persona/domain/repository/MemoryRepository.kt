package com.example.persona.domain.repository

import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MemoryRepository {
    fun observe(personaId: String): Flow<List<Memory>>
    val changes: StateFlow<Long>

    suspend fun save(
        personaId: String?,
        type: MemoryType,
        content: String,
        cloudAllowed: Boolean,
        sourceMessageId: String? = null,
        id: String? = null,
        expectedRevision: Int? = null,
        expiresAt: Long? = null
    ): Memory

    suspend fun delete(id: String, expectedRevision: Int)
    suspend fun deleteForPersona(personaId: String)
    suspend fun selectForPrompt(personaId: String, cloud: Boolean, query: String = ""): List<Memory>
}
