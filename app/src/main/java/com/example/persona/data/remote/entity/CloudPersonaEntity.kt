package com.example.persona.data.remote.entity

import com.example.persona.domain.model.Persona
import java.util.UUID

data class CloudPersonaEntity(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val avatarUrl: String = "",
    val postImageUrl: String = "",
    val traits: List<String> = emptyList(),
    val backstory: String = "",
    val creatorId: String = ""
) {
    fun toDomain(): Persona = Persona(
        id = id,
        name = name,
        avatarUrl = avatarUrl,
        postImageUrl = postImageUrl,
        traits = traits,
        backstory = backstory,
        creatorId = creatorId
    )
}

fun Persona.toCloudEntity(): CloudPersonaEntity = CloudPersonaEntity(
    id = id,
    name = name,
    avatarUrl = avatarUrl,
    postImageUrl = postImageUrl,
    traits = traits,
    backstory = backstory,
    creatorId = creatorId
)