package com.example.persona.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [Index(value = ["ownerId", "personaId", "deleted", "updatedAt"])]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val personaId: String?,
    val type: String,
    val content: String,
    val sourceMessageId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val cloudAllowed: Boolean = false,
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false
)
