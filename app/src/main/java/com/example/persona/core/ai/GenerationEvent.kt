package com.example.persona.core.ai

sealed interface GenerationEvent {
    data class Delta(
        val text: String
    ) : GenerationEvent

    data class Completed(
        val metrics: GenerationMetrics
    ) : GenerationEvent

    data class Stopped(
        val reason: StopReason,
        val partialText: String
    ) : GenerationEvent

    data class Failed(
        val backend: Backend,
        val error: GenerationError,
        val partialText: String,
        val fallbackPlanned: Boolean = false
    ) : GenerationEvent
}

data class GenerationMetrics(
    val firstTokenLatencyMs: Long?,
    val totalLatencyMs: Long,
    val outputTokens: Int,
    val tokensPerSecond: Double,
    val nativeInputTokens: Long? = null,
    val nativeOutputTokens: Long? = null,
    val prefillMicros: Long? = null,
    val decodeMicros: Long? = null,
    val samplingMicros: Long? = null
)

enum class StopReason {
    USER_REQUESTED,
    SESSION_REPLACED,
    ENGINE_RELEASED,
    REPETITION_LOOP
}

data class GenerationError(
    val type: GenerationErrorType,
    val message: String,
    val cause: Throwable? = null
)

enum class GenerationErrorType {
    NOT_CONFIGURED,
    HTTP,
    NETWORK,
    MODEL_NOT_READY,
    MODEL_LOAD_FAILED,
    MODEL_RUNTIME,
    EMPTY_RESPONSE,
    INPUT_BUDGET_EXCEEDED,
    UNKNOWN
}
