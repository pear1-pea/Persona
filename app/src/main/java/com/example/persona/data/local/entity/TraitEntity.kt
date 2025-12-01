package com.example.persona.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "traits",
    foreignKeys = [
        ForeignKey(
            entity = PersonaEntity::class,
            parentColumns = ["id"],
            childColumns = ["personaId"],
            onDelete = ForeignKey.CASCADE 
        )
    ],
    indices = [Index(value = ["personaId"])]
)
data class TraitEntity(
    @PrimaryKey(autoGenerate = true)
    val traitId: Long = 0,
    val personaId: String, 
    val traitContent: String 
)