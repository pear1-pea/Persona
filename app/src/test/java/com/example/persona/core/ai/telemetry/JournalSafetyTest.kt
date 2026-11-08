package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class JournalSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun attempt(number: Int = 0) = InferenceAttempt(
        requestHash = "a".repeat(64), attempt = number, task = AiTaskKind.CHAT,
        backend = Backend.MNN, modelFingerprint = null, startedAtEpochMs = 1_000,
        totalLatencyMs = 10, outcome = AttemptOutcome.COMPLETED
    )

    @Test
    fun `abandoned staging files are removed on read and clear without replay`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        journal.record(attempt())
        val abandoned = File(root, "inference-attempts-123.tmp").apply { writeText("stale") }
        assertEquals(listOf(attempt()), journal.snapshot())
        assertFalse(abandoned.exists())
        abandoned.writeText("stale")
        journal.clear()
        assertFalse(abandoned.exists())
        assertTrue(FileInferenceJournal(root, { 1_000 }).snapshot().isEmpty())
    }

    @Test
    fun `cancelled coroutine does not persist a new attempt`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        journal.record(attempt())
        try {
            withContext(Job().apply { cancel() }) { journal.record(attempt(1)) }
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(attempt()), journal.snapshot())
        }
    }

    @Test
    fun `reflection-created invalid values cannot bypass write validation`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_000 })
        journal.record(attempt())
        val malformed = Gson().fromJson(
            Gson().toJson(attempt(1)).replace("\"totalLatencyMs\":10", "\"totalLatencyMs\":-1"),
            InferenceAttempt::class.java
        )
        try {
            journal.record(malformed)
            throw AssertionError("Unsafe deserialized values must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(attempt()), journal.snapshot())
        }
    }

    @Test
    fun `failed rename leaves target intact and removes staging file`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val target = File(root, "inference-attempts.json").apply { mkdir() }
        val sentinel = File(target, "keep").apply { writeText("keep") }
        try {
            FileInferenceJournal(root, { 1_000 }).clear()
            throw AssertionError("Rename onto a nonempty directory must fail")
        } catch (_: IOException) {
            assertEquals("keep", sentinel.readText())
            assertEquals(listOf(target), root.listFiles()!!.toList())
        }
    }

    @Test
    fun `high water time persists before expiry so rollback cannot reset age`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 1_000L
        val journal = FileInferenceJournal(root, { now }, retentionMs = 1_000)
        journal.record(attempt())
        now = 1_999
        assertEquals(1, journal.snapshot().size)
        now = 1_500
        val reopened = FileInferenceJournal(root, { now }, retentionMs = 1_000)
        assertEquals(1, reopened.snapshot().size)
        reopened.record(attempt(1).copy(startedAtEpochMs = 999))
        assertEquals(listOf(attempt()), reopened.snapshot())
    }

    @Test
    fun `root metadata is validated and errors do not include private values`() {
        listOf(
            "{\"observedAtEpochMs\":null,\"attempts\":[]}",
            "{\"observedAtEpochMs\":-1,\"attempts\":[]}",
            "{\"observedAtEpochMs\":0,\"attempts\":null}",
            "{\"observedAtEpochMs\":0,\"attempts\":[],\"owner\":\"private\"}",
            "{\"observedAtEpochMs\":0,\"attempts\":[],\"attempts\":[]}",
            "{\"observedAtEpochMs\":9223372036854775808,\"attempts\":[]}"
        ).forEach { document ->
            val error = assertThrows(IOException::class.java) { JournalJson.read(document) }
            assertEquals("Invalid journal document", error.message)
            assertEquals(null, error.cause)
        }
    }
}
