package com.example.persona.core.ai

import com.example.persona.core.ai.telemetry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BufferedInferenceTelemetryTest {
    private fun attempt(index: Int = 1) = InferenceAttempt("a".repeat(64), index, AiTaskKind.CHAT,
        Backend.MNN, null, 1_000, 10, outcome = AttemptOutcome.COMPLETED)

    @Test fun `slow storage cannot block runtime and queue is bounded`() = runTest {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val sink = object : InferenceTelemetry {
            override suspend fun record(attempt: InferenceAttempt) { release.await(); writes++ }
            override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) = Unit
        }
        val buffer = BufferedInferenceTelemetry(sink, backgroundScope, capacity = 2)
        buffer.record(attempt())
        runCurrent()
        repeat(20) { buffer.record(attempt(it + 2)) }
        assertEquals(18L, buffer.droppedCommands)
        assertEquals(0, writes)
        release.complete(Unit)
        buffer.close()
        runCurrent()
        assertEquals(3, writes)
    }

    @Test fun `contract annotation follows its attempt and storage failure is isolated`() = runTest {
        val events = mutableListOf<String>()
        val sink = object : InferenceTelemetry {
            override suspend fun record(attempt: InferenceAttempt) {
                events += "record-${attempt.attempt}"
                if (attempt.attempt == 1) throw java.io.IOException("synthetic disk failure")
            }
            override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) {
                events += "contract-$attempt"
            }
        }
        val buffer = BufferedInferenceTelemetry(sink, backgroundScope)
        buffer.record(attempt())
        buffer.record(attempt(2))
        buffer.recordContract("a".repeat(64), 2, ContractStatus.ACCEPTED)
        buffer.close()
        runCurrent()
        assertEquals(listOf("record-1", "record-2", "contract-2"), events)
        assertEquals(1L, buffer.failedCommands)
        assertEquals(0L, buffer.droppedCommands)
    }
}
