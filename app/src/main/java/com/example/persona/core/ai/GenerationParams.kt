package com.example.persona.core.ai

data class GenerationParams(
    val temperature: Float = 0.6f,
    val topP: Float = 0.8f,
    val maxTokens: Int = 256
)
