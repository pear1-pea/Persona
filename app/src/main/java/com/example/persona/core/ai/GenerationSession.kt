package com.example.persona.core.ai

import java.util.UUID

data class GenerationSession(
    val id: String = UUID.randomUUID().toString()
)
