package com.example.persona.features

import android.app.Activity
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.persona.R
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.telemetry.AiTaskKind
import com.example.persona.core.ai.telemetry.AttemptOutcome
import com.example.persona.core.ai.telemetry.InferenceAttempt
import com.example.persona.features.diagnostics.DiagnosticsError
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P4DiagnosticsUiInstrumentedTest {
    @Test(timeout = 240_000L)
    fun actualHiltActivityCancellationRecreationExportAndClear() = runBlocking<Unit> {
        val ui = P4DiagnosticsUiFixture()
        var failure: Throwable? = null
        try {
            ui.prepare()
            ui.launch()
            ui.awaitState("initial empty journal") { !it.busy && it.total == 0 }
            val canaries = listOf("synthetic-account-${ui.runId}", "synthetic-prompt-${ui.runId}",
                "synthetic-response-${ui.runId}", "synthetic-source-${ui.runId}")
            val started = System.currentTimeMillis()
            val attempts = AttemptOutcome.entries.mapIndexed { index, outcome ->
                InferenceAttempt(P4DiagnosticsUiFixture.digest("${canaries.joinToString()}-$index"),
                    index, AiTaskKind.CHAT, Backend.MNN, null, started, 10L + index,
                    outcome = outcome)
            }
            ui.ownedHashes = attempts.map { it.requestHash }.toSet()
            attempts.forEach { ui.journal.record(it) }
            ui.tap(R.string.diagnostics_refresh)
            ui.awaitState("actual Hilt journal refreshed") { !it.busy && it.total == attempts.size }
            assertEquals(1, ui.state().completed)
            assertEquals(1, ui.state().failed)
            assertEquals(1, ui.state().cancelled)
            ui.assertCounts(attempts.size, 1, 1, 1, 2)
            ui.observed("hilt_activity_counts", "passed", "Five synthetic outcomes from the Activity's injected journal")

            // This phase really opens DocumentsUI; only Back is automated in that process.
            val beforePicker = ui.launchCount
            val originalVm = ui.model
            ui.tap(R.string.diagnostics_export)
            ui.awaitState("system picker reserved") { !it.busy && it.awaitingDocument }
            val pending = checkNotNull(ui.state().exportData)
            ui.awaitDocumentsWindow()
            ui.recreate()
            assertSame(originalVm, ui.model)
            assertEquals(pending, ui.state().exportData)
            ui.assertNoAdditionalLaunch(beforePicker + 1)
            ui.cancelSystemPicker()
            ui.awaitState("real system cancellation") { !it.busy && !it.awaitingDocument && it.exportData == null }
            assertNull(ui.state().error)
            assertEquals(attempts, ui.journal.snapshot())
            ui.assertExportEnabled()
            ui.observed("documents_ui_cancel_recreate", "passed", "Real system window, Back cancellation, retained VM, one launch")

            // The existing debug provider is read-only. The following phases explicitly
            // test raw ActivityResult routing, not successful system-picker navigation.
            ui.blockPicker()
            val cancelled = File(ui.evidence, "cancelled-destination.json").apply { writeText("cancel-sentinel") }
            ui.requestBlockedExport()
            ui.deliver(Activity.RESULT_CANCELED, Uri.fromFile(cancelled))
            ui.awaitState("cancelled raw result") { !it.busy && !it.awaitingDocument && it.exportData == null }
            assertEquals("cancel-sentinel", cancelled.readText())
            assertNull(ui.state().error)
            ui.observed("cancel_result_no_write", "passed", "RESULT_CANCELED ignores even a supplied destination")

            ui.requestBlockedExport()
            val snapshot = checkNotNull(ui.state().exportData)
            val exported = File(ui.evidence, "exported-diagnostics.json")
            assertFalse(exported.exists())
            ui.deliver(Activity.RESULT_OK, Uri.fromFile(exported))
            ui.awaitState("private file stream completed") { !it.busy && !it.awaitingDocument && it.exportData == null }
            assertNull(ui.state().error)
            assertEquals(snapshot, exported.readText(Charsets.UTF_8))
            assertPrivateSchema(exported.readText(), attempts.map { it.requestHash }.toSet(), canaries)
            ui.observed("private_file_result_readback", "passed",
                "file:// result, actual Activity callback and ContentResolver write; not SAF content success")
            ui.observations.put("exportSha256", P4DiagnosticsUiFixture.digest(exported.readText()))

            val document = ui.registerReadOnlyDocument()
            val source = checkNotNull(ui.context.contentResolver.openInputStream(document)).use { it.readBytes() }
            val opens = ui.providerOpens()
            ui.requestBlockedExport()
            ui.deliver(Activity.RESULT_OK, document)
            ui.awaitState("read-only content provider refused output") { !it.busy && it.error == DiagnosticsError.EXPORT }
            assertArrayEquals(source, ui.providerSource.readBytes())
            assertEquals(opens, ui.providerOpens())
            ui.assertExportEnabled()
            ui.assertText(R.string.diagnostics_error_export)
            ui.observed("debug_provider_write_rejected", "passed", "Valid content URI; current provider rejects wt without changing bytes")

            ui.requestBlockedExport()
            val stale = checkNotNull(ui.state().exportData)
            val launches = ui.launchCount
            ui.recreate()
            assertSame(originalVm, ui.model)
            assertEquals(stale, ui.state().exportData)
            ui.assertNoAdditionalLaunch(launches)
            ui.clearThroughDialog(confirm = false)
            assertEquals(stale, ui.state().exportData)
            assertEquals(attempts, ui.journal.snapshot())
            ui.clearThroughDialog(confirm = true)
            ui.awaitState("clear revoked payload but kept reservation") {
                !it.busy && it.total == 0 && it.exportData == null && it.awaitingDocument
            }
            assertTrue(ui.journal.snapshot().isEmpty())
            ui.assertCounts(0, 0, 0, 0, 0)
            ui.deliver(Activity.RESULT_OK, document)
            ui.awaitState("stale result rejected") { !it.busy && !it.awaitingDocument && it.error == DiagnosticsError.EXPIRED }
            assertArrayEquals(source, ui.providerSource.readBytes())
            assertEquals(opens, ui.providerOpens())
            ui.assertText(R.string.diagnostics_error_expired)
            ui.observed("clear_revokes_pending_snapshot", "passed", "UI confirmation invalidates payload; late content result never opens output")

            val injectedJournal = ui.journal
            ui.finishActivity()
            ui.launch()
            assertNotSame(originalVm, ui.model)
            assertSame(injectedJournal, ui.journal)
            ui.awaitState("fresh Activity has no old rows") {
                !it.busy && it.total == 0 && it.exportData == null && !it.awaitingDocument
            }
            assertNull(ui.state().error)
            ui.requestBlockedExport()
            val empty = File(ui.evidence, "after-clear-diagnostics.json")
            ui.deliver(Activity.RESULT_OK, Uri.fromFile(empty))
            ui.awaitState("fresh empty export completed") { !it.busy && !it.awaitingDocument }
            assertNull(ui.state().error)
            assertEquals(0, JSONArray(empty.readText()).length())
            ui.observed("reentry_has_no_old_records", "passed", "New Activity/VM, same Hilt singleton, real empty file readback")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            ui.complete(failure)
        }
    }

    private fun assertPrivateSchema(json: String, hashes: Set<String>, canaries: List<String>) {
        canaries.forEach { assertFalse("Synthetic content leaked", json.contains(it)) }
        val fields = setOf("requestHash", "attempt", "task", "backend", "modelFingerprint",
            "startedAtEpochMs", "totalLatencyMs", "firstTokenLatencyMs", "outputTokens",
            "nativeInputTokens", "nativeOutputTokens", "prefillMicros", "decodeMicros",
            "samplingMicros", "outcome", "errorType", "stopReason", "contractStatus", "trigger")
        val rows = JSONArray(json)
        assertEquals(hashes.size, rows.length())
        val actualHashes = mutableSetOf<String>()
        val outcomes = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            assertEquals("Only explicitly allowed metrics may be exported", fields, row.keys().asSequence().toSet())
            val hash = row.getString("requestHash")
            assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
            actualHashes.add(hash)
            outcomes.add(row.getString("outcome"))
            assertEquals(index, row.getInt("attempt"))
            assertEquals("CHAT", row.getString("task"))
            assertEquals("MNN", row.getString("backend"))
            assertEquals("NOT_EVALUATED", row.getString("contractStatus"))
            assertEquals("INITIAL", row.getString("trigger"))
            assertTrue(row.getLong("startedAtEpochMs") > 0)
            assertEquals(10L + index, row.getLong("totalLatencyMs"))
            (fields - setOf("requestHash", "attempt", "task", "backend", "startedAtEpochMs",
                "totalLatencyMs", "outcome", "contractStatus", "trigger")).forEach {
                assertTrue("Unexpected free-text or unseeded metric: $it", row.isNull(it))
            }
        }
        assertEquals(hashes, actualHashes)
        assertEquals(AttemptOutcome.entries.map { it.name }.toSet(), outcomes)
    }
}
