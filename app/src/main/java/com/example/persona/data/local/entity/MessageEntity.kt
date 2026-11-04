package com.example.persona.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = PersonaEntity::class,
            parentColumns = ["id"],
            childColumns = ["personaId"],
            onDelete = ForeignKey.CASCADE // cascade delete
        )
    ],
    indices = [Index(value = ["personaId", "ownerId"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,          // UUID
    val personaId: String,   
    val ownerId: String = "OFFLINE_USER",
    val content: String,
    val isFromUser: Boolean, 
    val timestamp: Long,
    val status: String = "NORMAL",
    @ColumnInfo(defaultValue = "0") val localOnly: Boolean = false,
    @ColumnInfo(defaultValue = "0") val excludeFromHistory: Boolean = false
)
