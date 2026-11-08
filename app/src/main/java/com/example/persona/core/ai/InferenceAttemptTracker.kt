package com.example.persona.core.ai

import com.example.persona.core.ai.telemetry.AiTaskKind
import com.example.persona.core.ai.telemetry.AttemptOutcome
import com.example.persona.core.ai.telemetry.AttemptTrigger
import com.example.persona.core.ai.telemetry.ContractStatus
import com.example.persona.core.ai.telemetry.InferenceAttempt
import com.example.persona.core.ai.telemetry.InferenceTelemetry
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class InferenceAttemptTracker(
    private val telemetry: InferenceTelemetry,
    private val nanoTime: () -> Long = System::nanoTime,
    private val epochTime: () -> Long = System::currentTimeMillis
) {
    fun trace(session: GenerationSession, backend: Backend, modelIdentity: String?,
        events: Flow<GenerationEvent>): Flow<GenerationEvent> = flow {
        val attempt = session.inferenceAttempts.incrementAndGet()
        val start = nanoTime()
        val epoch = epochTime()
        var firstTokenMs: Long? = null
        var metrics: GenerationMetrics? = null
        var outcome = AttemptOutcome.ABANDONED
        var error: GenerationErrorType? = null
        var stop: StopReason? = null
        try {
            events.collect { event ->
                when (event) {
                    is GenerationEvent.Delta -> if (firstTokenMs == null && event.text.isNotEmpty()) {
                        firstTokenMs = elapsed(start)
                    }
                    is GenerationEvent.Completed -> if (outcome == AttemptOutcome.ABANDONED) {
                        outcome = AttemptOutcome.COMPLETED
                        metrics = event.metrics
                    }
                    is GenerationEvent.Failed -> if (outcome == AttemptOutcome.ABANDONED) {
                        outcome = AttemptOutcome.FAILED
                        error = event.error.type
                    }
                    is GenerationEvent.Stopped -> if (outcome == AttemptOutcome.ABANDONED) {
                        outcome = AttemptOutcome.STOPPED
                        stop = event.reason
                    }
                }
                emit(event)
            }
        } catch (cancelled: CancellationException) {
            outcome = AttemptOutcome.CANCELLED
            stop = session.stopReason
            throw cancelled
        } catch (failure: Throwable) {
            if (outcome == AttemptOutcome.ABANDONED) {
                outcome = AttemptOutcome.FAILED
                error = GenerationErrorType.UNKNOWN
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                // Diagnostics must never replace a generation result.
                try {
                    val record = InferenceAttempt(
                        requestHash = fingerprint(session.id), attempt = attempt,
                        task = if (session.memoryExtraction) AiTaskKind.MEMORY_EXTRACTION else AiTaskKind.CHAT,
                        backend = backend, modelFingerprint = modelIdentity?.let(::fingerprint),
                        startedAtEpochMs = epoch, totalLatencyMs = elapsed(start),
                        firstTokenLatencyMs = firstTokenMs, outputTokens = metrics?.outputTokens?.takeIf { it >= 0 },
                        nativeInputTokens = metrics?.nativeInputTokens?.takeIf { it >= 0 }, nativeOutputTokens = metrics?.nativeOutputTokens?.takeIf { it >= 0 },
                        prefillMicros = metrics?.prefillMicros?.takeIf { it >= 0 }, decodeMicros = metrics?.decodeMicros?.takeIf { it >= 0 },
                        samplingMicros = metrics?.samplingMicros?.takeIf { it >= 0 }, outcome = outcome, errorType = error, stopReason = stop,
                        trigger = when {
                            session.contractUpgrade -> AttemptTrigger.CONTRACT_FALLBACK
                            attempt > 1 -> AttemptTrigger.RUNTIME_FALLBACK
                            else -> AttemptTrigger.INITIAL
                        }
                    )
                    telemetry.record(record)
                } catch (_: Exception) { }
            }
        }
    }

    suspend fun contract(session: GenerationSession, accepted: Boolean) {
        try {
            telemetry.recordContract(fingerprint(session.id), session.inferenceAttempts.get(),
                if (accepted) ContractStatus.ACCEPTED else ContractStatus.REJECTED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { }
    }

    private fun elapsed(start: Long): Long = ((nanoTime() - start) / 1_000_000).coerceAtLeast(0)

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
