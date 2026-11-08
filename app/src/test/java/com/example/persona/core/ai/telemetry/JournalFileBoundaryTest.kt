package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class JournalFileBoundaryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun attempt(number: Int = 0) = InferenceAttempt(
        requestHash = "a".repeat(64), attempt = number, task = AiTaskKind.CHAT,
        backend = Backend.MNN, modelFingerprint = null, startedAtEpochMs = 1_000,
        totalLatencyMs = 10, outcome = AttemptOutcome.COMPLETED
    )

    @Test
    fun `real replacement never exposes missing partial or mixed committed documents`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        val file = File(root, "inference-attempts.json")
        journal.record(attempt())
        val reading = CompletableDeferred<Unit>()
        val done = AtomicBoolean(false)
        val reader = async(Dispatchers.IO) {
            var observations = 0
            do {
                val rows = JournalJson.read(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)).attempts
                assertEquals((0 until rows.size).toList(), rows.map { it.attempt })
                observations++
                reading.complete(Unit)
            } while (!done.get())
            observations
        }
        try {
            reading.await()
            withTimeout(10_000) {
                for (i in 1..40) {
                    val previous = Files.readAllBytes(file.toPath())
                    while (true) {
                        try {
                            journal.record(attempt(i))
                            break
                        } catch (failure: IOException) {
                            // Windows MoveFileEx cannot replace an open target, even with delete sharing.
                            // Retry only in this external-reader stress test, after checking atomic failure.
                            assertEquals("Journal storage unavailable", failure.message)
                            assertEquals(null, failure.cause)
                            assertTrue(failure.suppressed.isEmpty())
                            assertArrayEquals(previous, Files.readAllBytes(file.toPath()))
                            assertEquals(listOf(file), root.listFiles()!!.toList())
                            yield()
                        }
                    }
                    val rows = JournalJson.read(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)).attempts
                    assertEquals((0..i).toList(), rows.map { it.attempt })
                }
            }
        } finally {
            done.set(true)
        }
        assertTrue(reader.await() > 1)
        assertEquals(41, journal.snapshot().size)
        assertEquals(listOf(file), root.listFiles()!!.toList())
    }

    @Test
    fun `storage exceptions expose only fixed text without paths causes or suppressed errors`() = runBlocking {
        for (blockedStaging in listOf(false, true)) {
            val root = temporaryFolder.newFolder("private-owner-$blockedStaging")
            val file = File(root, "inference-attempts.json")
            val blocked = if (blockedStaging) {
                file.writeText("private-response")
                File(root, "inference-attempts-blocked.tmp")
            } else {
                file
            }
            assertTrue(blocked.mkdir())
            val sentinel = File(blocked, "private-response").apply { writeText("private-response") }
            val journal = FileInferenceJournal(root, { 1_000 })
            for (operation in listOf<suspend () -> Any?>(
                { journal.snapshot() }, { journal.exportJson() }, { journal.record(attempt()) },
                { journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED) }, { journal.clear() }
            )) {
                try {
                    operation()
                    throw AssertionError("Unreadable journal structure must be rejected")
                } catch (failure: IOException) {
                    assertEquals("Journal storage unavailable", failure.message)
                    assertEquals(null, failure.cause)
                    assertTrue(failure.suppressed.isEmpty())
                    assertFalse(failure.stackTraceToString().contains("private-owner"))
                }
                assertEquals("private-response", sentinel.readText())
            }
        }
    }

    @Test
    fun `cancel during operation before rename preserves committed file and permits retry`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        journal.record(attempt())
        val file = File(root, "inference-attempts.json")
        val committed = file.readText()
        for (clear in listOf(false, true)) {
            val job = Job()
            var cancel = true
            val cancelled = FileInferenceJournal(root, { if (cancel) job.cancel(); 1_000 })
            try {
                withContext(job) {
                    if (clear) cancelled.clear() else cancelled.record(attempt(1))
                }
                throw AssertionError("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(committed, file.readText())
                assertEquals(listOf(file), root.listFiles()!!.toList())
            }
            cancel = false
            cancelled.record(attempt(1))
            assertEquals(2, cancelled.snapshot().size)
            file.writeText(committed)
        }
        journal.record(attempt(1))
        assertEquals(2, journal.snapshot().size)
    }

    @Test
    fun `invalid clear metadata and legacy Gson non JSON null spellings are rejected safely`() {
        val prefix = "{\"observedAtEpochMs\":1000,\"attempts\":[],\"clearEpochMs\":"
        for (value in listOf("null", "-1", "1001", "\"1000\"", "1e3", "9223372036854775808")) {
            val error = assertThrows(IOException::class.java) { JournalJson.read("$prefix$value}") }
            assertEquals("Invalid journal document", error.message)
            assertEquals(null, error.cause)
        }
        val valid = JournalJson.write(JournalState(1_000, listOf(attempt())))
        for (poisoned in listOf(valid.replace(":null", ":NULL"), valid.replace(":null", ":nUlL"), "\uFEFF$valid")) {
            val error = assertThrows(IOException::class.java) { JournalJson.read(poisoned) }
            assertEquals("Invalid journal document", error.message)
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun `exact size boundary is readable but oversized and malformed UTF8 cannot export`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        val file = File(root, "inference-attempts.json")
        val valid = JournalJson.write(JournalState(1_000, listOf(attempt())))
        file.writeText(valid.padEnd(512 * 1024, ' '))
        assertEquals(listOf(attempt()), journal.snapshot())
        for (bytes in listOf(
            valid.padEnd(512 * 1024 + 1, ' ').toByteArray(),
            valid.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        )) {
            file.writeBytes(bytes)
            try {
                journal.exportJson()
                throw AssertionError("Invalid bytes must not export")
            } catch (failure: IOException) {
                assertEquals("Journal storage unavailable", failure.message)
                assertEquals(null, failure.cause)
            }
        }
    }

    @Test
    fun `overfull valid input is bounded before export and rewritten on disk`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val file = File(root, "inference-attempts.json")
        file.writeText(JournalJson.write(JournalState(1_000, (0..299).map { attempt(it) })))
        val journal = FileInferenceJournal(root, { 1_000 })
        val exported = JournalJson.read("{\"observedAtEpochMs\":1000,\"attempts\":${journal.exportJson()}}")
        assertEquals((44..299).toList(), exported.attempts.map { it.attempt })
        assertEquals(exported.attempts, JournalJson.read(file.readText()).attempts)
        assertTrue(file.length() <= 512 * 1024)
    }

    @Test
    fun `valid JSON whitespace and escaped known field names remain compatible`() {
        val valid = JournalJson.write(JournalState(1_000, listOf(attempt())))
        val escaped = " \r\n\t" + valid.replace("requestHash", "request\\u0048ash") + " \n"
        assertEquals(listOf(attempt()), JournalJson.read(escaped).attempts)
    }
}
