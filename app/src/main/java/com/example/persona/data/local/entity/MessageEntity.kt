package com.example.persona.data.local.entity

import androidx.room.Entity
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
    indices = [Index(value = ["personaId"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,          // UUID
    val personaId: String,   
    val content: String,
    val isFromUser: Boolean, 
    val timestamp: Long      
)