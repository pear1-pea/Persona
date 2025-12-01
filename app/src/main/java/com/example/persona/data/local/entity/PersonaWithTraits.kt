package com.example.persona.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class PersonaWithTraits(
    @Embedded val persona: PersonaEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "personaId"
    )
    val traits: List<TraitEntity>
)