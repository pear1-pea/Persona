package com.example.persona.features

import android.os.Bundle
import android.os.Process
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.data.local.entity.MemoryCandidateEntity
import com.example.persona.data.local.entity.MemoryPreferencesEntity
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P3CurationRecoveryInstrumentedTest {
    @Test(timeout = 180_000L)
    fun prepareOrVerifyAfterHostForceStop() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("p3RecoveryPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val ui = P3CurationUiFixture()
        val directory = File(ui.context.filesDir, "p3-curation-recovery")
        val checkpoint = File(directory, "state.json")
        if (phase == "prepare") {
            if (checkpoint.exists()) {
                ui.database.close()
                error("An earlier P3 recovery checkpoint requires verification or explicit fixture cleanup")
            }
            val id = "p3-recovery-${UUID.randomUUID()}"
            var handedToHost = false
            try {
                ui.prepare(id)
                ui.launch(id)
                ui.awaitUi("recovery screen loaded") { !it.loading && it.messages.size == 3 }
                ui.tap(R.string.curation_enable)
                ui.awaitUi("curation enabled") { it.preferences.enabled && it.operation == null }
                ui.propose("$id-first")
                val pending = ui.state().candidates.single()
                ui.tap(R.string.curation_edit)
                ui.awaitUi("unconfirmed editor opened") { it.draft?.candidateId == pending.id && it.conflictsLoaded }
                ui.edit(R.string.memory_content, "合成未确认编辑，不应在 force-stop 后自动保存。")
                ui.awaitUi("unconfirmed edit captured") { it.draft?.content?.startsWith("合成未确认") == true }
                // This is a synthetic interrupted-task row, not a claim of live model extraction.
                val runningId = "$id-running-fixture"
                assertTrue(ui.database.memoryCurationDao().insert(MemoryCandidateEntity(
                    runningId, ui.owner, id, "$id-second", "synthetic-task-hash", "合成未完成任务缓冲",
                    "PREFERENCE", "preference:general", "p3-synthetic-interrupted-task", true,
                    "RUNNING", null, System.currentTimeMillis())) != -1L)
                assertTrue(ui.database.memoryDao().selectForPrompt(ui.owner, id, false, System.currentTimeMillis()).isEmpty())
                check(directory.isDirectory || directory.mkdirs())
                val state = Checkpoint(id, pending.id, pending.content, runningId, Process.myPid(), PROCESS_TOKEN,
                    ui.previousPreferences, ui.database.memoryCurationDao().preferences(ui.owner)?.revision ?: error("No consent"))
                // Sync the checkpoint before telling the host it may terminate this process.
                java.io.FileOutputStream(checkpoint).use { output ->
                    output.write(Gson().toJson(state).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                handedToHost = true
                ui.instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "P3_CURATION_RECOVERY_READY pid=${state.pid} synthetic_running_fixture=true\n")
                    putInt("p3PreparedPid", state.pid)
                })
                // The host must force-stop com.example.persona.validation while this test is still waiting.
                repeat(90) { delay(1_000L) }
                handedToHost = false
                error("Host did not force-stop the prepared validation process within 90 seconds")
            } finally {
                if (!handedToHost) {
                    ui.finish()
                    try { ui.cleanup(id); checkpoint.delete() } finally { ui.database.close() }
                }
            }
        } else {
            try {
                check(checkpoint.isFile) { "Run prepare and force-stop the validation process first" }
                val state = Gson().fromJson(checkpoint.readText(), Checkpoint::class.java)
                check(state.personaId.startsWith("p3-recovery-"))
                val stoppedPid = InstrumentationRegistry.getArguments().getString("p3ForceStoppedPid")?.toIntOrNull()
                check(stoppedPid == state.pid) { "Pass -e p3ForceStoppedPid with the PID from READY after host force-stop" }
                check(state.processToken != PROCESS_TOKEN && state.pid != Process.myPid()) {
                    "Verification must execute in a new process"
                }
                ui.previousPreferences = state.previousPreferences
                ui.preferenceCaptured = true
                val report = linkedMapOf<String, Any?>(
                    "kind" to "p3_actual_process_death_synthetic_curation_task",
                    "synthetic_only" to true, "live_model_extraction" to false,
                    "prepared_pid" to state.pid, "verified_pid" to Process.myPid(),
                    "draft_restoration_claimed" to false
                )
                try {
                    // The independent Room fixture has no recovery callback; observe RUNNING before opening UI.
                    assertEquals("RUNNING", ui.database.memoryCurationDao().candidate(ui.owner, state.runningId)?.status)
                    assertEquals("PENDING", ui.database.memoryCurationDao().candidate(ui.owner, state.pendingId)?.status)
                    assertTrue(ui.database.memoryDao().selectForPrompt(ui.owner, state.personaId, false,
                        System.currentTimeMillis()).isEmpty())
                    ui.launch(state.personaId)
                    ui.awaitUi("pending candidate recovered in fresh Activity") {
                        !it.loading && it.operation == null && it.preferences.enabled &&
                            it.candidates.singleOrNull()?.id == state.pendingId
                    }
                    assertNull(ui.state().draft)
                    assertEquals(state.pendingContent, ui.state().candidates.single().content)
                    assertEquals(state.consentRevision, ui.database.memoryCurationDao().preferences(ui.owner)?.revision)
                    val task = checkNotNull(ui.database.memoryCurationDao().candidate(ui.owner, state.runningId))
                    assertEquals("FAILED", task.status)
                    assertEquals("", task.content)
                    assertNull(task.memoryId)
                    delay(750L)
                    assertNull(ui.state().operation)
                    assertEquals(listOf(state.pendingId), ui.state().candidates.map { it.id })
                    assertTrue(ui.database.memoryDao().selectForPrompt(ui.owner, state.personaId, false,
                        System.currentTimeMillis()).isEmpty())
                    ui.tap(R.string.curation_source)
                    ui.awaitUi("recovered source available") { it.sources?.singleOrNull()?.content == P3CurationUiFixture.FIRST }
                    ui.assertDialogContains(P3CurationUiFixture.FIRST)
                    ui.closeSources()
                    ui.tap(R.string.curation_edit)
                    ui.awaitUi("recovered editor ready") { it.draft?.candidateId == state.pendingId && it.conflictsLoaded }
                    ui.edit(R.string.memory_content, "合成恢复后通过界面明确确认。")
                    ui.tap(R.string.curation_create_new)
                    ui.tap(R.string.curation_confirm)
                    ui.awaitUi("recovered candidate saved once") { it.draft == null && it.candidates.isEmpty() && it.operation == null }
                    val accepted = checkNotNull(ui.database.memoryCurationDao().candidate(ui.owner, state.pendingId))
                    assertEquals("ACCEPTED", accepted.status)
                    val memoryId = checkNotNull(accepted.memoryId)
                    assertEquals("合成恢复后通过界面明确确认。", ui.database.memoryDao().findOwned(memoryId, ui.owner)?.content)
                    ui.finish()
                    ui.launch(state.personaId)
                    ui.awaitUi("reopen without duplication") { !it.loading && it.operation == null && it.candidates.isEmpty() &&
                        it.memories.any { memory -> memory.id == memoryId } }
                    onView(withText(R.string.curation_empty)).check(matches(withText(R.string.curation_empty)))
                    assertEquals(listOf(memoryId), ui.database.memoryDao().selectForPrompt(ui.owner, state.personaId,
                        false, System.currentTimeMillis()).map { it.id })
                    report["status"] = "passed"
                    report["checks"] = listOf("pending_candidate_retained", "synthetic_running_task_failed_and_scrubbed",
                        "no_automatic_save_or_restart_observed", "source_visible", "explicit_ui_confirmation", "no_duplicate_on_reopen")
                } catch (error: Throwable) {
                    report["status"] = "failed"
                    report["failure_class"] = error.javaClass.simpleName
                    throw error
                } finally {
                    ui.finish()
                    try {
                        ui.cleanup(state.personaId)
                        report["fixture_cleanup"] = true
                        check(checkpoint.delete()) { "Could not remove recovery checkpoint" }
                    } finally {
                        File(directory, "observations.json").writeText(Gson().toJson(report))
                    }
                }
            } finally { ui.database.close() }
        }
    }

    private data class Checkpoint(
        val personaId: String,
        val pendingId: String,
        val pendingContent: String,
        val runningId: String,
        val pid: Int,
        val processToken: String,
        val previousPreferences: MemoryPreferencesEntity?,
        val consentRevision: Long
    )

    private companion object {
        val PROCESS_TOKEN: String = UUID.randomUUID().toString()
    }
}
