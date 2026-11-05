package com.example.persona.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "memory_candidates", indices = [Index(value = ["ownerId", "personaId", "status"])])
data class MemoryCandidateEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val personaId: String,
    val sourceMessageId: String,
    val sourceHash: String,
    val content: String,
    val type: String,
    val topicKey: String,
    val extractorVersion: String,
    val manual: Boolean,
    val status: String,
    val memoryId: String?,
    val createdAt: Long
)

@Entity(tableName = "memory_preferences")
data class MemoryPreferencesEntity(
    @PrimaryKey val ownerId: String,
    val enabled: Boolean,
    val cloudExtractionAllowed: Boolean,
    val revision: Long = 0
)

@Entity(tableName = "memory_summary_sources", primaryKeys = ["memoryId", "sourceMessageId"],
    indices = [Index(value = ["ownerId", "sourceMessageId"])])
data class MemorySummarySourceEntity(
    val memoryId: String,
    val sourceMessageId: String,
    val ownerId: String,
    val sourceHash: String
)
