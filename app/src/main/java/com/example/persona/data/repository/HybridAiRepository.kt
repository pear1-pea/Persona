package com.example.persona.data.repository

import com.example.persona.core.ai.EdgeAiEngine
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAiRepository @Inject constructor(
    private val cloudRepository: CloudChatRepository,
    private val edgeEngine: EdgeAiEngine
) {

    // define mode enum
    enum class Mode { CLOUD, EDGE }

    suspend fun initEdgeModel() {
        edgeEngine.initModel()
    }

    suspend fun evaluateComplexity(text: String): Float {
        return edgeEngine.routeComplexity(text)
    }

    fun streamResponse(
        mode: Mode,
        systemPrompt: String,
        userMessage: String
    ): Flow<String> {
        return when (mode) {
            Mode.CLOUD -> {
                cloudRepository.streamResponse(systemPrompt, userMessage)
            }
            Mode.EDGE -> {
                edgeEngine.generateResponse(systemPrompt, userMessage)
            }
        }
    }
}