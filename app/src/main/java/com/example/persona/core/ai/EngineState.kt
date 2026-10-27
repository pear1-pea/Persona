package com.example.persona.core.ai

sealed interface EngineState {
    data object Idle : EngineState
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Error(
        val reason: String,
        val type: LocalModelFailureType = LocalModelFailureType.UNKNOWN
    ) : EngineState
}
