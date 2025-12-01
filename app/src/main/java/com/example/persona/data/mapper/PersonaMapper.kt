package com.example.persona.data.mapper

import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity
import com.example.persona.domain.model.Persona

// Convert complex DB objects to domain model
fun PersonaWithTraits.toDomain(): Persona {
    return Persona(
        id = this.persona.id,
        name = this.persona.name,
        avatarUrl = this.persona.avatarUrl,
        postImageUrl = this.persona.postImageUrl,
        backstory = this.persona.backstory,
        creatorId = this.persona.creatorId,
        traits = this.traits.map { it.traitContent }
    )
}

// Convert domain object to main table entity
fun Persona.toEntity(): PersonaEntity {
    return PersonaEntity(
        id = this.id,
        name = this.name,
        avatarUrl = this.avatarUrl,
        postImageUrl = this.postImageUrl,
        backstory = this.backstory,
        creatorId = this.creatorId
    )
}

// Convert domain object to trait entity list
fun Persona.toTraitEntities(): List<TraitEntity> {
    return this.traits.map { content ->
        TraitEntity(
            personaId = this.id,
            traitContent = content
        )
    }
}