package com.example.persona.domain.model

data class Persona(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val postImageUrl: String,
    val traits: List<String>,
    val backstory: String,
    val creatorId: String
)