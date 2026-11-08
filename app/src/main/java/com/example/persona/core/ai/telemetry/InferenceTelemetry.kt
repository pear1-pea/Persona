package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.StopReason

enum class AiTaskKind { CHAT, MEMORY_EXTRACTION }
enum class AttemptOutcome { COMPLETED, FAILED, STOPPED, CANCELLED, ABANDONED }
// Parser/structural acceptance is independent of engine completion and does not assess semantics.
enum class ContractStatus { NOT_EVALUATED, ACCEPTED, REJECTED }
enum class AttemptTrigger { INITIAL, RUNTIME_FALLBACK, CONTRACT_FALLBACK }

data class InferenceAttempt(
    val requestHash: String,
    val attempt: Int,
    val task: AiTaskKind,
    val backend: Backend,
    val modelFingerprint: String?,
    val startedAtEpochMs: Long,
    val totalLatencyMs: Long,
    val firstTokenLatencyMs: Long? = null,
    val outputTokens: Int? = null,
    val nativeInputTokens: Long? = null,
    val nativeOutputTokens: Long? = null,
    val prefillMicros: Long? = null,
    val decodeMicros: Long? = null,
    val samplingMicros: Long? = null,
    val outcome: AttemptOutcome,
    val errorType: GenerationErrorType? = null,
    val stopReason: StopReason? = null,
    val contractStatus: ContractStatus = ContractStatus.NOT_EVALUATED,
    val trigger: AttemptTrigger = AttemptTrigger.INITIAL
) {
    init {
        require(isDigest(requestHash) && (modelFingerprint == null || isDigest(modelFingerprint))) {
            "Telemetry identifiers must be SHA-256 hex digests"
        }
        require(attempt >= 0 && startedAtEpochMs >= 0 && totalLatencyMs >= 0) {
            "Telemetry counters must be nonnegative"
        }
        require(listOf(firstTokenLatencyMs, outputTokens?.toLong(), nativeInputTokens,
            nativeOutputTokens, prefillMicros, decodeMicros, samplingMicros).all { it == null || it >= 0 }) {
            "Telemetry metrics must be nonnegative when present"
        }
    }
}

internal fun isDigest(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

interface InferenceTelemetry {
    suspend fun record(attempt: InferenceAttempt)
    suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus)
}

object NoopInferenceTelemetry : InferenceTelemetry {
    override suspend fun record(attempt: InferenceAttempt) = Unit
    override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) = Unit
}
