package com.example.persona.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey
    val id: String, 
    val name: String,
    val avatarUrl: String,
    val postImageUrl: String,
    val backstory: String,
    val creatorId: String 
)