package com.example.persona.domain.repository

import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryCandidate
import com.example.persona.domain.model.MemoryPreferences
import com.example.persona.domain.model.MemoryType
import kotlinx.coroutines.flow.Flow

interface MemoryCurationRepository {
    fun observe(personaId: String): Flow<List<MemoryCandidate>>
    suspend fun preferences(): MemoryPreferences
    suspend fun configure(enabled: Boolean, cloudExtractionAllowed: Boolean)
    suspend fun propose(personaId: String, sourceMessageId: String, explicitOnly: Boolean = false)
    suspend fun proposeWithModel(personaId: String, sourceMessageId: String, cloud: Boolean = false)
    suspend fun ignore(id: String)
    suspend fun conflicts(id: String): List<Memory>
    suspend fun confirm(id: String, content: String, type: MemoryType, shared: Boolean = false,
        cloudAllowed: Boolean = false, expiresAt: Long? = null,
        replaceId: String? = null, expectedRevision: Int? = null): Memory
    suspend fun summarize(personaId: String): Memory?
    suspend fun summarySources(memoryId: String): List<String>
}
