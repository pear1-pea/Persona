package com.example.persona.core.memory

import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.StopReason
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InferencePriorityGateTest {
    @Test fun `chat preempts extraction and cleanup does not remove chat ownership`() {
        val stopped = mutableListOf<GenerationSession>()
        val gate = InferencePriorityGate { stopped += it }
        val extraction = GenerationSession("extract")
        val chat = GenerationSession("chat")
        assertTrue(gate.begin(extraction, true))
        assertTrue(gate.begin(chat, false))
        assertEquals(listOf(extraction), stopped)
        assertEquals(StopReason.SESSION_REPLACED, extraction.stopReason)
        gate.end(extraction)
        assertFalse(gate.begin(GenerationSession(), true))
        gate.end(chat)
        assertTrue(gate.begin(GenerationSession(), true))
    }

    @Test fun `chat waits for displaced extraction cleanup before dispatch`() = runBlocking {
        val gate = InferencePriorityGate {}
        val extraction = GenerationSession()
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            try {
                assertTrue(gate.begin(extraction, true, currentCoroutineContext()[Job]))
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(10); cleaned = true }
                gate.end(extraction)
            }
        }
        started.await()
        val chat = GenerationSession()
        assertTrue(gate.begin(chat, false))
        withTimeout(1000) { gate.awaitBackgroundCleanup() }
        assertTrue(cleaned)
        assertTrue(job.isCancelled)
        gate.end(chat)
    }

    @Test fun `only one extraction can acquire an idle runtime`() {
        val gate = InferencePriorityGate { error("Nothing should be stopped") }
        val first = GenerationSession()
        assertTrue(gate.begin(first, true))
        assertFalse(gate.begin(GenerationSession(), true))
        gate.end(first)
        assertTrue(gate.begin(GenerationSession(), true))
    }

    @Test fun `parallel chats retain priority until the last request completes`() {
        val gate = InferencePriorityGate {}
        val first = GenerationSession()
        val second = GenerationSession()
        assertTrue(gate.begin(first, false))
        assertTrue(gate.begin(second, false))
        gate.end(first)
        assertFalse(gate.begin(GenerationSession(), true))
        gate.end(second)
        assertTrue(gate.begin(GenerationSession(), true))
    }
}
