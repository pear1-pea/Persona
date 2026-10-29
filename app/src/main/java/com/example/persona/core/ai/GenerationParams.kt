package com.example.persona.core.ai

data class GenerationParams(
    val temperature: Float = 0.6f,
    val topP: Float = 0.8f,
    val maxTokens: Int = 256
) {
    init {
        require(temperature.isFinite() && temperature in 0f..2f) {
            "temperature must be in [0, 2]"
        }
        require(topP.isFinite() && topP > 0f && topP <= 1f) {
            "topP must be in (0, 1]"
        }
        require(maxTokens > 0) { "maxTokens must be positive" }
    }
}
