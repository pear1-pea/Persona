package com.example.persona.domain.model

enum class MemoryType { PREFERENCE, FACT, GOAL, RELATIONSHIP }

data class Memory(
    val id: String,
    val ownerId: String,
    val personaId: String?,
    val type: MemoryType,
    val content: String,
    val sourceMessageId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    val cloudAllowed: Boolean = false,
    val revision: Int = 1,
    val deleted: Boolean = false
)
