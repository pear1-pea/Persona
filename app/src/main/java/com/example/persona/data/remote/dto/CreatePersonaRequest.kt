package com.example.persona.data.remote.dto

data class CreatePersonaRequest(
    val name: String,
    val traits: List<String>,
    val backstory: String,
    val isPublic: Boolean
)

typealias UpdatePersonaRequest = CreatePersonaRequest
