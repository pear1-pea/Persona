package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class JournalClearTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun attempt(start: Long, number: Int = 0) = InferenceAttempt(
        requestHash = "a".repeat(64), attempt = number, task = AiTaskKind.CHAT,
        backend = Backend.MNN, modelFingerprint = null, startedAtEpochMs = start,
        totalLatencyMs = 10, outcome = AttemptOutcome.COMPLETED
    )

    @Test
    fun `clear rejects running attempts including same millisecond completions and annotations`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 1_000L
        val journal = FileInferenceJournal(root, { now })
        val finish = CompletableDeferred<Unit>()
        val running = async {
            finish.await()
            journal.record(attempt(1_000))
            journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
        }
        journal.record(attempt(999, 1))
        journal.clear()
        finish.complete(Unit)
        running.await()
        journal.record(attempt(999, 1))
        assertEquals("[]", journal.exportJson())
        now++
        val fresh = attempt(now, 2)
        journal.record(fresh)
        journal.recordContract("a".repeat(64), 0, ContractStatus.REJECTED)
        assertEquals(listOf(fresh), journal.snapshot())
    }

    @Test
    fun `clear watermark survives reopen rollback and repeated clear`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 1_000L
        FileInferenceJournal(root, { now }).clear()
        now = 900
        val reopened = FileInferenceJournal(root, { now })
        reopened.clear()
        now = 1_001
        val again = FileInferenceJournal(root, { now })
        again.record(attempt(1_000))
        again.record(attempt(900, 1))
        assertEquals("[]", again.exportJson())
        again.record(attempt(now, 2))
        assertEquals(listOf(attempt(now, 2)), again.snapshot())
    }

    @Test
    fun `clear on a fresh instance respects persisted observed time during rollback`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 2_000L
        FileInferenceJournal(root, { now }).record(attempt(now))
        now = 1_000
        FileInferenceJournal(root, { now }).clear()
        now = 2_001
        val reopened = FileInferenceJournal(root, { now })
        reopened.record(attempt(2_000))
        assertTrue(reopened.snapshot().isEmpty())
        reopened.record(attempt(now, 1))
        assertEquals(listOf(attempt(now, 1)), reopened.snapshot())
    }

    @Test
    fun `clear watermark filters persisted old attempts before contract annotation or export`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val journal = FileInferenceJournal(root, { 1_001 })
        journal.record(attempt(1_000))
        val file = File(root, "inference-attempts.json")
        file.writeText(file.readText().replace("\"attempts\":", "\"clearEpochMs\":1000,\"attempts\":"))
        journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
        assertEquals("[]", journal.exportJson())
        assertTrue(FileInferenceJournal(root, { 1_001 }).snapshot().isEmpty())
    }

    @Test
    fun `legacy documents without clear watermark accept epoch zero`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val file = File(root, "inference-attempts.json")
        file.writeText("{\"observedAtEpochMs\":0,\"attempts\":[]}")
        val journal = FileInferenceJournal(root, { 0 })
        journal.record(attempt(0))
        assertEquals(listOf(attempt(0)), journal.snapshot())
        journal.clear()
        journal.record(attempt(0))
        assertEquals("[]", FileInferenceJournal(root, { 0 }).exportJson())
    }

    @Test
    fun `clear maximum timestamp cannot wrap around and revive old records`() = runBlocking {
        val root = temporaryFolder.newFolder()
        FileInferenceJournal(root, { Long.MAX_VALUE }).clear()
        val reopened = FileInferenceJournal(root, { Long.MAX_VALUE })
        reopened.record(attempt(Long.MAX_VALUE))
        assertEquals("[]", reopened.exportJson())
    }

    @Test
    fun `failed clear leaves committed data and in memory watermark unchanged`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 900L
        var failCommit = false
        var rejectedCommits = 0
        val journal = FileInferenceJournal(root, { now }, retentionMs = 1_000, committer = { temporary, committed ->
            if (failCommit) {
                assertEquals(JournalState(2_000, emptyList(), 2_000), JournalJson.read(temporary.readText()))
                assertEquals(listOf(attempt(1_000)), JournalJson.read(committed.readText()).attempts)
                rejectedCommits++
                throw IOException("private-owner clear failure").apply {
                    addSuppressed(IOException("private-response"))
                }
            }
            JournalCommit.replace(temporary, committed)
        })
        journal.clear()
        now = 1_000
        journal.record(attempt(1_000))
        val file = File(root, "inference-attempts.json")
        val committed = file.readText()
        failCommit = true
        now = 2_000
        try {
            journal.clear()
            throw AssertionError("Clear must fail when committing the staged file")
        } catch (failure: IOException) {
            assertEquals("Journal storage unavailable", failure.message)
            assertEquals(null, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertEquals(committed, file.readText())
        }
        assertEquals(1, rejectedCommits)
        assertEquals(listOf(file), root.listFiles()!!.toList())
        failCommit = false
        now = 1_000
        journal.record(attempt(1_000, 1))
        assertEquals(listOf(attempt(1_000), attempt(1_000, 1)), journal.snapshot())
        assertEquals(journal.snapshot(), FileInferenceJournal(root, { now }).snapshot())
        assertEquals(900L, JournalJson.read(file.readText()).clearEpochMs)
    }

    @Test
    fun `clear recovers corruption without losing known high water time`() = runBlocking {
        val root = temporaryFolder.newFolder()
        var now = 2_000L
        val journal = FileInferenceJournal(root, { now })
        journal.clear()
        File(root, "inference-attempts.json").writeText("private-corrupt-response")
        now = 1_000
        journal.clear()
        now = 2_001
        val reopened = FileInferenceJournal(root, { now })
        reopened.record(attempt(2_000))
        assertEquals("[]", reopened.exportJson())
    }
}
