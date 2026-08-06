package com.example.persona.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LocalAiEngine {
    val state: StateFlow<EngineState>

    suspend fun initialize(model: InstalledModel): Boolean

    fun streamResponse(
        session: GenerationSession,
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        params: GenerationParams = GenerationParams()
    ): Flow<String>

    fun stopGeneration(session: GenerationSession)

    fun release()
}
