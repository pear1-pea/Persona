package com.example.persona.domain.model

data class Message(
    val id: String,
    val personaId: String,
    val content: String,
    val isFromUser: Boolean, // true = user, false = AI
    val timestamp: Long = System.currentTimeMillis()
)