package com.example.persona.core.ai

import com.example.persona.core.ai.telemetry.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InferenceAttemptTrackerTest {
    private class Sink : InferenceTelemetry {
        val records = mutableListOf<InferenceAttempt>()
        override suspend fun record(attempt: InferenceAttempt) { records += attempt }
        override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) {
            val index = records.indexOfFirst { it.requestHash == requestHash && it.attempt == attempt }
            if (index >= 0) records[index] = records[index].copy(contractStatus = status)
        }
    }
    private val metrics = GenerationMetrics(null, 20, 3, 1.0)

    @Test fun `records observed timing and fingerprints without generated text`() = runTest {
        val sink = Sink()
        var nanos = 0L
        val tracker = InferenceAttemptTracker(sink, { nanos }, { 1_000 })
        val session = GenerationSession("private-account-like-input")
        val events = tracker.trace(session, Backend.MNN, "private-model-like-input", flow {
            nanos = 5_000_000
            emit(GenerationEvent.Delta("private response"))
            nanos = 20_000_000
            emit(GenerationEvent.Completed(metrics))
        }).toList()
        assertEquals(2, events.size)
        val record = sink.records.single()
        assertEquals(5L, record.firstTokenLatencyMs)
        assertEquals(20L, record.totalLatencyMs)
        assertEquals(64, record.requestHash.length)
        assertEquals(64, record.modelFingerprint?.length)
        assertFalse(record.toString().contains("private"))
        assertNull(record.nativeOutputTokens)
        assertNull(record.decodeMicros)
        assertEquals(ContractStatus.NOT_EVALUATED, record.contractStatus)
    }

    @Test fun `runtime fallback increments attempt and distinguishes actual backends`() = runTest {
        val sink = Sink()
        val tracker = InferenceAttemptTracker(sink)
        val session = GenerationSession()
        tracker.trace(session, Backend.MNN, null, flowOf(GenerationEvent.Failed(Backend.MNN,
            GenerationError(GenerationErrorType.MODEL_RUNTIME, "private error"), "private partial"))).toList()
        tracker.trace(session, Backend.CLOUD, null, flowOf(GenerationEvent.Completed(metrics))).toList()
        assertEquals(listOf(1, 2), sink.records.map { it.attempt })
        assertEquals(listOf(Backend.MNN, Backend.CLOUD), sink.records.map { it.backend })
        assertEquals(sink.records[0].requestHash, sink.records[1].requestHash)
        assertEquals(AttemptTrigger.RUNTIME_FALLBACK, sink.records[1].trigger)
        assertEquals(GenerationErrorType.MODEL_RUNTIME, sink.records[0].errorType)
    }

    @Test fun `contract annotation never implies semantic quality`() = runTest {
        val sink = Sink()
        val tracker = InferenceAttemptTracker(sink)
        val session = GenerationSession().apply {
            memoryExtraction = true
            contractUpgrade = true
            inferenceAttempts.set(1)
        }
        tracker.trace(session, Backend.CLOUD, null, flowOf(GenerationEvent.Completed(metrics))).toList()
        tracker.contract(session, true)
        assertEquals(2, sink.records.single().attempt)
        assertEquals(AiTaskKind.MEMORY_EXTRACTION, sink.records.single().task)
        assertEquals(AttemptTrigger.CONTRACT_FALLBACK, sink.records.single().trigger)
        assertEquals(ContractStatus.ACCEPTED, sink.records.single().contractStatus)
    }

    @Test fun `cancellation records cleanup without swallowing cancellation`() = runTest {
        val sink = Sink()
        val tracker = InferenceAttemptTracker(sink)
        val session = GenerationSession()
        val job = launch {
            tracker.trace(session, Backend.MNN, null, flow {
                emit(GenerationEvent.Delta("private"))
                awaitCancellation()
            }).toList()
        }
        runCurrent()
        session.requestStop(StopReason.USER_REQUESTED)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(AttemptOutcome.CANCELLED, sink.records.single().outcome)
        assertEquals(StopReason.USER_REQUESTED, sink.records.single().stopReason)
    }

    @Test fun `diagnostic IO failure cannot replace completed generation`() = runTest {
        val failing = object : InferenceTelemetry {
            override suspend fun record(attempt: InferenceAttempt) { error("disk full") }
            override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) { error("disk full") }
        }
        val tracker = InferenceAttemptTracker(failing)
        val session = GenerationSession()
        assertTrue(tracker.trace(session, Backend.CLOUD, null,
            flowOf(GenerationEvent.Completed(metrics))).toList().single() is GenerationEvent.Completed)
        tracker.contract(session, true)
    }

    @Test fun `unavailable native metrics cannot break generation`() = runTest {
        val sink = Sink()
        val tracker = InferenceAttemptTracker(sink)
        tracker.trace(GenerationSession(), Backend.MNN, null, flowOf(GenerationEvent.Completed(
            metrics.copy(nativeInputTokens = -1, decodeMicros = -1)))).toList()
        assertNull(sink.records.single().nativeInputTokens)
        assertNull(sink.records.single().decodeMicros)
    }
}
