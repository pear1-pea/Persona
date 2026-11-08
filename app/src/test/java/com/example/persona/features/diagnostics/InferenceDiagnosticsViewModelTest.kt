package com.example.persona.features.diagnostics

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.telemetry.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class InferenceDiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val journal = mock<FileInferenceJournal>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun row(outcome: AttemptOutcome) = InferenceAttempt(
        requestHash = "a".repeat(64), attempt = 0, task = AiTaskKind.CHAT,
        backend = Backend.MNN, modelFingerprint = null, startedAtEpochMs = 0,
        totalLatencyMs = 10, outcome = outcome
    )

    @Test fun `counts distinguish completed failed cancelled and other outcomes`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(AttemptOutcome.entries.map(::row))
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.total)
        assertEquals(1, vm.uiState.value.completed)
        assertEquals(1, vm.uiState.value.failed)
        assertEquals(1, vm.uiState.value.cancelled)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun `load errors expose only fixed error and can retry`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenAnswer { throw IOException("private prompt account") }
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        assertEquals(DiagnosticsError.LOAD, vm.uiState.value.error)
        reset(journal)
        whenever(journal.snapshot()).thenReturn(emptyList())
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }

    @Test fun `picker cancellation discards payload and permits new export`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("[]")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        assertTrue(vm.uiState.value.busy)
        advanceUntilIdle()
        assertTrue(vm.markDocumentLaunched())
        assertFalse(vm.markDocumentLaunched())
        vm.cancelExport()
        assertNull(vm.uiState.value.exportData)
        assertFalse(vm.uiState.value.awaitingDocument)
        assertNull(vm.uiState.value.error)
        vm.prepareExport()
        advanceUntilIdle()
        assertEquals("[]", vm.uiState.value.exportData)
    }

    @Test fun `clear invalidates pending data even when clear fails`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("old")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        vm.markDocumentLaunched()
        whenever(journal.clear()).thenAnswer { throw IOException("private") }
        vm.clear()
        assertNull(vm.uiState.value.exportData)
        advanceUntilIdle()
        assertEquals(DiagnosticsError.CLEAR, vm.uiState.value.error)
        var written = false
        vm.exportTo { written = true }
        advanceUntilIdle()
        assertFalse(written)
    }

    @Test fun `successful clear resets counts and pending export`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(listOf(row(AttemptOutcome.FAILED)))
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.clear()
        advanceUntilIdle()
        verify(journal).clear()
        assertEquals(0, vm.uiState.value.total)
        assertEquals(0, vm.uiState.value.failed)
        assertNull(vm.uiState.value.exportData)
    }

    @Test fun `write failure consumes payload and never exposes free error`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("[]")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        vm.markDocumentLaunched()
        vm.exportTo { throw IOException("account prompt") }
        awaitOperation(vm)
        assertEquals(DiagnosticsError.EXPORT, vm.uiState.value.error)
        assertNull(vm.uiState.value.exportData)
    }

    @Test fun `process recreation without snapshot cannot write callback destination`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        var written = false
        vm.exportTo { written = true }
        advanceUntilIdle()
        assertFalse(written)
        assertEquals(DiagnosticsError.EXPIRED, vm.uiState.value.error)
    }

    @Test fun `cancellation is rethrown without diagnostic error and releases busy`() = runTest(dispatcher) {
        var operation: kotlinx.coroutines.Job? = null
        whenever(journal.snapshot()).thenAnswer { invocation ->
            val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<*>
            operation = continuation.context[kotlinx.coroutines.Job]
            throw CancellationException("cancel")
        }
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
        assertTrue(operation!!.isCancelled)
    }

    private fun awaitOperation(vm: InferenceDiagnosticsViewModel) {
        repeat(400) {
            dispatcher.scheduler.runCurrent()
            if (!vm.uiState.value.busy) return
            Thread.sleep(5)
        }
        fail("Diagnostic operation did not finish")
    }

    @Test fun `success writes once off main and consumes payload before writing`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("[]")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        assertTrue(vm.markDocumentLaunched())
        val mainThread = Thread.currentThread()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        vm.exportTo { data ->
            assertNotSame(mainThread, Thread.currentThread())
            assertNull(vm.uiState.value.exportData)
            assertEquals("[]", data)
            writes.incrementAndGet()
        }
        awaitOperation(vm)
        assertEquals(1, writes.get())
        assertNull(vm.uiState.value.error)
        vm.exportTo { writes.incrementAndGet() }
        advanceUntilIdle()
        assertEquals(1, writes.get())
    }

    @Test fun `retained VM does not relaunch picker and blocks overlapping exports`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("[]")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        vm.prepareExport()
        advanceUntilIdle()
        assertTrue(vm.markDocumentLaunched())
        assertFalse(vm.markDocumentLaunched())
        vm.prepareExport()
        advanceUntilIdle()
        verify(journal, times(1)).exportJson()
        assertTrue(vm.uiState.value.awaitingDocument)
        assertEquals("[]", vm.uiState.value.exportData)
    }

    @Test fun `preparation and picker launch failures allow explicit retry`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenAnswer { throw IOException("private") }
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        assertEquals(DiagnosticsError.EXPORT, vm.uiState.value.error)
        assertNull(vm.uiState.value.exportData)
        reset(journal)
        whenever(journal.exportJson()).thenReturn("[]")
        vm.prepareExport()
        advanceUntilIdle()
        vm.markDocumentLaunched()
        vm.exportFailed()
        assertNull(vm.uiState.value.exportData)
        assertFalse(vm.uiState.value.awaitingDocument)
        vm.prepareExport()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals("[]", vm.uiState.value.exportData)
    }

    @Test fun `clear keeps picker reserved until stale callback is discarded`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("old")
        val vm = InferenceDiagnosticsViewModel(journal)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        vm.markDocumentLaunched()
        vm.clear()
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        verify(journal, times(1)).exportJson()
        assertTrue(vm.uiState.value.awaitingDocument)
        vm.exportTo { fail("Stale payload was written") }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.awaitingDocument)
        assertNull(vm.uiState.value.exportData)
    }

    @Test fun `clearing lifecycle cancels active write without error`() = runTest(dispatcher) {
        whenever(journal.snapshot()).thenReturn(emptyList())
        whenever(journal.exportJson()).thenReturn("[]")
        val vm = InferenceDiagnosticsViewModel(journal)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("diagnostics", vm)
        advanceUntilIdle()
        vm.prepareExport()
        advanceUntilIdle()
        vm.markDocumentLaunched()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.exportTo {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        runCurrent()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(2_000) { entered.await() }
        }
        store.clear()
        awaitOperation(vm)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.exportData)
    }

}
