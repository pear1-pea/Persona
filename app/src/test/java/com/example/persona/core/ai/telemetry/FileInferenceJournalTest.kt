package com.example.persona.core.ai.telemetry

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.StopReason
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class FileInferenceJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_000_000L
    private fun root() = temporaryFolder.newFolder()
    private fun journal(root: File, limit: Int = 256, ttl: Long = 1_000) =
        FileInferenceJournal(root, { now }, ttl, limit)

    private fun attempt(number: Int = 0) = InferenceAttempt(
        requestHash = "a".repeat(64), attempt = number, task = AiTaskKind.CHAT,
        backend = Backend.MNN, modelFingerprint = null, startedAtEpochMs = now,
        totalLatencyMs = 50, outcome = AttemptOutcome.COMPLETED
    )

    private fun journalFile(root: File) = root.listFiles()!!.single { it.isFile }

    @Test
    fun `round trip retains all enums metrics and independent contract status`() = runBlocking {
        val root = root()
        val journal = journal(root)
        val expected = AttemptOutcome.entries.mapIndexed { index, outcome ->
            attempt(index).copy(
                task = AiTaskKind.MEMORY_EXTRACTION, backend = Backend.CLOUD,
                modelFingerprint = "B".repeat(64), firstTokenLatencyMs = 10, outputTokens = 3,
                nativeInputTokens = 4, nativeOutputTokens = 5, prefillMicros = 6,
                decodeMicros = 7, samplingMicros = 8, outcome = outcome,
                errorType = GenerationErrorType.NETWORK, stopReason = StopReason.USER_REQUESTED,
                contractStatus = ContractStatus.REJECTED, trigger = AttemptTrigger.CONTRACT_FALLBACK
            )
        }
        expected.forEach { journal.record(it) }
        assertEquals(expected, journal(root).snapshot())
        assertEquals(expected.size, JsonParser().parse(journal.exportJson()).asJsonArray.size())
    }

    @Test
    fun `missing optional metrics stay null and zero stays measured zero`() = runBlocking {
        val root = root()
        val journal = journal(root)
        val missing = attempt()
        journal.record(missing)
        journal.record(attempt(1).copy(outputTokens = 0, nativeOutputTokens = 0))
        val rows = journal(root).snapshot()
        assertEquals(missing, rows.first())
        assertEquals(null, rows.first().firstTokenLatencyMs)
        assertEquals(null, rows.first().nativeInputTokens)
        assertEquals(0, rows.last().outputTokens)
        assertEquals(0L, rows.last().nativeOutputTokens)
        assertEquals(ContractStatus.NOT_EVALUATED, rows.first().contractStatus)
        assertEquals(AttemptTrigger.INITIAL, rows.first().trigger)
    }

    @Test
    fun `rotation is persisted and keeps newest appended attempts`() = runBlocking {
        val root = root()
        val journal = journal(root, limit = 3)
        repeat(8) { journal.record(attempt(it)) }
        assertEquals(listOf(5, 6, 7), journal(root, limit = 3).snapshot().map { it.attempt })
        assertEquals(1, root.listFiles()!!.size)
        assertTrue(journalFile(root).length() <= 512 * 1024)
    }

    @Test
    fun `parallel records and annotations lose no attempts`() = runBlocking {
        val journal = journal(root())
        (0 until 64).map { index ->
            async(Dispatchers.Default) {
                journal.record(attempt(index))
                journal.recordContract("a".repeat(64), index, ContractStatus.ACCEPTED)
            }
        }.awaitAll()
        val rows = journal.snapshot()
        assertEquals((0 until 64).toSet(), rows.map { it.attempt }.toSet())
        assertTrue(rows.all { it.contractStatus == ContractStatus.ACCEPTED })
    }

    @Test
    fun `TTL is rechecked on snapshot and export and persisted across rollback`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        now += 999
        assertEquals(1, journal.snapshot().size)
        now++
        assertEquals("[]", journal.exportJson())
        now -= 1_000
        assertTrue(journal(root).snapshot().isEmpty())
    }

    @Test
    fun `clock rollback evicts future records and they cannot reappear`() = runBlocking {
        val root = root()
        journal(root).record(attempt())
        now--
        assertTrue(journal(root).snapshot().isEmpty())
        now++
        assertTrue(journal(root).snapshot().isEmpty())
    }

    @Test
    fun `future record is rejected without changing old records`() = runBlocking {
        val root = root()
        val journal = journal(root)
        val original = attempt()
        journal.record(original)
        try {
            journal.record(attempt(1).copy(startedAtEpochMs = now + 1))
            throw AssertionError("Future record must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(original), journal.snapshot())
        }
    }

    @Test
    fun `expired input never enters journal`() = runBlocking {
        val journal = journal(root())
        journal.record(attempt().copy(startedAtEpochMs = now - 1_000))
        assertTrue(journal.snapshot().isEmpty())
    }

    @Test
    fun `invalid digests and negative metrics are rejected`() {
        listOf("", "private prompt", "a".repeat(63), "g".repeat(64), "a".repeat(65)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { attempt().copy(requestHash = invalid) }
            assertThrows(IllegalArgumentException::class.java) { attempt().copy(modelFingerprint = invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { attempt(-1) }
        assertThrows(IllegalArgumentException::class.java) { attempt().copy(totalLatencyMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { attempt().copy(nativeOutputTokens = -1) }
    }

    @Test
    fun `corrupt and oversized files are rejected and clear restores usable journal`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        val file = journalFile(root)
        for (text in listOf("{broken", "null", "[] trailing", "[null]")) {
            file.writeText(text)
            expectRejected { journal.snapshot() }
            expectRejected { journal.exportJson() }
        }
        RandomAccessFile(file, "rw").use { it.setLength(512L * 1024 + 1) }
        expectRejected { journal.snapshot() }
        journal.clear()
        assertEquals("[]", journal.exportJson())
        now++
        journal.record(attempt(1))
        assertEquals(1, journal.snapshot().single().attempt)
    }

    @Test
    fun `private unknown fields null required fields and unknown enums cannot export`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        val file = journalFile(root)
        val valid = file.readText()
        val variants = listOf(
            valid.replace("\"requestHash\":", "\"owner\":\"secret-owner\",\"requestHash\":"),
            valid.replace("\"requestHash\":", "\"prompt\":\"secret-prompt\",\"requestHash\":"),
            valid.replace("\"requestHash\":", "\"response\":\"secret-response\",\"requestHash\":"),
            valid.replace("\"requestHash\":", "\"errorMessage\":\"secret-error\",\"requestHash\":"),
            valid.replace("\"requestHash\":\"${"a".repeat(64)}\"", "\"requestHash\":null"),
            valid.replace("\"task\":\"CHAT\"", "\"task\":null"),
            valid.replace("\"backend\":\"MNN\"", "\"backend\":\"secret-backend\""),
            valid.replace("\"outcome\":\"COMPLETED\"", "\"outcome\":null"),
            valid.replace("\"attempt\":0", "\"attempt\":null"),
            valid.replace("\"totalLatencyMs\":50", "\"totalLatencyMs\":-1"),
            valid.replace("\"requestHash\":\"${"a".repeat(64)}\"", "\"requestHash\":\"secret-id\""),
            valid.replace("\"contractStatus\":\"NOT_EVALUATED\"", "\"contractStatus\":null"),
            valid.replace("\"trigger\":\"INITIAL\"", "\"trigger\":\"secret-trigger\"")
        )
        variants.forEach { poisoned ->
            assertTrue(poisoned != valid)
            file.writeText(poisoned)
            expectRejected { journal.exportJson() }
        }
        journal.clear()
        assertFalse(journal.exportJson().contains("secret"))
    }

    @Test
    fun `strict parser rejects duplicate fields coercion and trailing documents`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        val file = journalFile(root)
        val valid = file.readText()
        listOf(
            valid.replace("\"attempt\":0", "\"attempt\":0,\"attempt\":1"),
            valid.replace("\"attempt\":0", "\"attempt\":\"0\""),
            valid.replace("\"attempt\":0", "\"attempt\":0.5"),
            valid.replace("\"attempt\":0", "\"attempt\":2147483648"),
            valid.replace("\"attempt\":0,", ""),
            "$valid {}", valid.replace("\"task\"", "task")
        ).forEach {
            file.writeText(it)
            expectRejected { journal.snapshot() }
        }
    }

    @Test
    fun `contract annotations update only matching rows and persist idempotently`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        journal.record(attempt(1).copy(trigger = AttemptTrigger.RUNTIME_FALLBACK))
        journal.record(attempt().copy(requestHash = "b".repeat(64)))
        repeat(2) { journal.recordContract("a".repeat(64), 0, ContractStatus.REJECTED) }
        val rows = journal(root).snapshot()
        assertEquals(3, rows.size)
        assertEquals(ContractStatus.REJECTED, rows.first().contractStatus)
        assertEquals(AttemptOutcome.COMPLETED, rows.first().outcome)
        assertTrue(rows.drop(1).all { it.contractStatus == ContractStatus.NOT_EVALUATED })
    }

    @Test
    fun `missing annotations do not create rows or resurrect rotated attempts`() = runBlocking {
        val root = root()
        val journal = journal(root, limit = 1)
        journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
        assertTrue(journal.snapshot().isEmpty())
        journal.record(attempt())
        journal.record(attempt(1))
        journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
        assertEquals(listOf(attempt(1)), journal.snapshot())
    }

    @Test
    fun `contract annotation after TTL never resurrects an expired row`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        now += 1_000
        journal.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
        now -= 1_000
        assertTrue(journal(root).snapshot().isEmpty())
        assertEquals("[]", journal.exportJson())
    }

    @Test
    fun `missing persisted contract annotations default to not evaluated`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        val file = journalFile(root)
        file.writeText(file.readText()
            .replace(",\"contractStatus\":\"NOT_EVALUATED\"", "")
            .replace(",\"trigger\":\"INITIAL\"", ""))
        val row = journal.snapshot().single()
        assertEquals(ContractStatus.NOT_EVALUATED, row.contractStatus)
        assertEquals(AttemptTrigger.INITIAL, row.trigger)
    }

    @Test
    fun `clear persists empty state and preserves unrelated files`() = runBlocking {
        val root = root()
        val journal = journal(root)
        journal.record(attempt())
        val unrelated = File(root, "unrelated.txt").apply { writeText("keep") }
        journal.clear()
        assertTrue(journal(root).snapshot().isEmpty())
        assertEquals("[]", journal.exportJson())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun `write failure preserves previous committed file`() = runBlocking {
        val root = root()
        var failCommit = false
        var rejectedCommits = 0
        val journal = FileInferenceJournal(root, { now }, committer = { temporary, committed ->
            if (failCommit) {
                assertTrue(temporary.isFile)
                assertEquals(listOf(attempt(), attempt(1)), JournalJson.read(temporary.readText()).attempts)
                assertEquals(listOf(attempt()), JournalJson.read(committed.readText()).attempts)
                rejectedCommits++
                throw IOException("private-owner commit failure")
            }
            JournalCommit.replace(temporary, committed)
        })
        journal.record(attempt())
        val file = journalFile(root)
        val previous = file.readText()
        failCommit = true
        expectRejected { journal.record(attempt(1)) }
        assertEquals(1, rejectedCommits)
        assertEquals(previous, file.readText())
        failCommit = false
        assertEquals(listOf(attempt()), journal.snapshot())
        assertEquals(listOf(attempt()), journal(root).snapshot())
        assertEquals(1, root.listFiles()!!.size)
        journal.record(attempt(1))
        assertEquals(listOf(attempt(), attempt(1)), journal(root).snapshot())
    }

    @Test
    fun `cancellation propagates and preserves committed records`() = runBlocking {
        val root = root()
        journal(root).record(attempt())
        val cancelled = FileInferenceJournal(root, { throw CancellationException("cancelled") })
        try {
            cancelled.record(attempt(1))
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(attempt()), journal(root).snapshot())
        }
    }

    @Test
    fun `default retention and record count apply without configuration`() = runBlocking {
        val root = root()
        val journal = FileInferenceJournal(root, { now })
        repeat(260) { journal.record(attempt(it)) }
        assertEquals(256, journal.snapshot().size)
        now += 7L * 24 * 60 * 60 * 1000
        assertTrue(journal.snapshot().isEmpty())
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { journal(root(), limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { journal(root(), ttl = 0) }
    }

    @Test
    fun `noop accepts recording and annotations without persistence`() = runBlocking {
        NoopInferenceTelemetry.record(attempt())
        NoopInferenceTelemetry.recordContract("a".repeat(64), 0, ContractStatus.ACCEPTED)
    }

    private suspend fun expectRejected(action: suspend () -> Any?) {
        try {
            action()
            throw AssertionError("Unsafe journal must be rejected")
        } catch (_: IOException) {
            // Corrupt input and storage failures are reported to the best-effort caller.
        }
    }
}
