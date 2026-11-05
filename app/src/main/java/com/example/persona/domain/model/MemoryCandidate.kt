package com.example.persona.domain.model

data class MemoryCandidate(
    val id: String,
    val ownerId: String,
    val personaId: String,
    val sourceMessageId: String,
    val sourceHash: String,
    val content: String,
    val type: MemoryType,
    val topicKey: String,
    val status: String,
    val memoryId: String?,
    val extractorVersion: String,
    val manual: Boolean,
    val createdAt: Long
)

data class MemoryPreferences(
    val enabled: Boolean = false,
    val cloudExtractionAllowed: Boolean = false
)
