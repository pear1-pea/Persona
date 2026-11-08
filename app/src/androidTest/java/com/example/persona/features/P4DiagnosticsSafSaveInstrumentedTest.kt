package com.example.persona.features

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.persona.R
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.telemetry.AiTaskKind
import com.example.persona.core.ai.telemetry.AttemptOutcome
import com.example.persona.core.ai.telemetry.InferenceAttempt
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// The host selects a fresh QA filename in the actual DocumentsUI and reads it back.
@RunWith(AndroidJUnit4::class)
class P4DiagnosticsSafSaveInstrumentedTest {
    @Test(timeout = 180_000L)
    fun realSystemPickerSaveCompletesActivityExport() = runBlocking<Unit> {
        val ui = P4DiagnosticsUiFixture()
        var failure: Throwable? = null
        try {
            ui.prepare()
            ui.launch()
            ui.awaitState("initial journal") { !it.busy && it.total == 0 }
            delay(20)
            val attempt = InferenceAttempt("d".repeat(64), 1, AiTaskKind.CHAT, Backend.MNN, null,
                System.currentTimeMillis(), 10, outcome = AttemptOutcome.COMPLETED)
            ui.ownedHashes = setOf(attempt.requestHash)
            ui.journal.record(attempt)
            ui.tap(R.string.diagnostics_refresh)
            ui.awaitState("synthetic row displayed") { !it.busy && it.total == 1 }
            val expected = ui.journal.exportJson()
            File(ui.evidence, "expected-export.json").writeText(expected)
            ui.tap(R.string.diagnostics_export)
            ui.awaitState("real picker opened") { !it.busy && it.awaitingDocument }
            ui.awaitDocumentsWindow()
            capturePicker(ui)
            ui.instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "P4_DIAGNOSTICS_SAVE_READY\n")
            })
            withTimeout(90_000L) {
                while (ui.state().let { it.busy || it.awaitingDocument || it.exportData != null }) {
                    capturePicker(ui)
                    delay(150)
                }
            }
            ui.instrumentation.waitForIdleSync()
            assertNull(ui.state().error)
            assertEquals(listOf(attempt), ui.journal.snapshot())
            ui.observations.put("successfulSafContentExport", "activity_write_completed_host_readback_required")
                .put("systemPickerSaveNavigation", "host_selected_real_system_document")
                .put("successfulOutputRoute", "real_system_CreateDocument_result")
            ui.observed("real_system_save_callback", "passed", "Host must compare its owned output to expected-export.json")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            ui.complete(failure)
        }
    }
    @Suppress("DEPRECATION")
    private fun capturePicker(ui: P4DiagnosticsUiFixture) {
        val root = ui.instrumentation.uiAutomation.rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString()
            if (packageName !in setOf("com.android.documentsui", "com.google.android.documentsui")) return
            val fields = JSONArray()
            val buttons = JSONArray()
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30 || visited++ > 500) return
                val text = node.text?.toString().orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                val editable = node.isEditable && (text == "persona-inference-diagnostics.json" ||
                    text.matches(Regex("persona-p4-qa-[a-z0-9-]+\\.json")))
                val save = node.isClickable && node.isEnabled &&
                    text.lowercase(Locale.ROOT) in setOf("保存", "save", "保存文件")
                if (editable || save) {
                    val item = JSONObject().put("text", text).put("id", node.viewIdResourceName)
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    if (editable) fields.put(item) else buttons.put(item)
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { walk(child, depth + 1) } finally { child.recycle() }
                }
            }
            walk(root, 0)
            val temporary = File(ui.evidence, "picker-controls.tmp")
            temporary.writeText(JSONObject().put("package", packageName).put("fields", fields)
                .put("save", buttons).toString())
            check(temporary.renameTo(File(ui.evidence, "picker-controls.json")))
        } finally { root.recycle() }
    }

}
