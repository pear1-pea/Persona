package com.example.persona.data.remote.dto

import com.example.persona.domain.model.Persona

data class PersonaDto(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val postImageUrl: String,
    val traits: List<String> = emptyList(),
    val backstory: String,
    val creatorId: String,
    val isPublic: Boolean = true
)

fun PersonaDto.toDomain(): Persona = Persona(
    id = id,
    name = name,
    avatarUrl = avatarUrl,
    postImageUrl = postImageUrl,
    traits = traits,
    backstory = backstory,
    creatorId = creatorId,
    isPublic = isPublic
)
