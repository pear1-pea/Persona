package com.example.persona.features

import android.content.Intent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryPreferencesEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.features.memory.CurationError
import com.example.persona.features.memory.MemoryActivity
import com.example.persona.features.memory.MemoryCurationActivity
import com.example.persona.features.memory.MemoryCurationUiState
import com.example.persona.features.memory.MemoryCurationViewModel
import com.example.persona.features.memory.formatMemoryExpiry
import com.example.persona.features.memory.parseMemoryExpiry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P3UiAcceptanceInstrumentedTest {
    @Test(timeout = 180_000L)
    fun manualCandidateSourcesExpiryRecreationCasIgnoreAndSummaryThroughUi() = runBlocking<Unit> {
        val ui = P3CurationUiFixture()
        val id = "p3-ui-${UUID.randomUUID()}"
        try {
            ui.prepare(id)
            ui.launch(id)
            ui.awaitUi("initial disabled screen") { !it.loading && it.messages.size == 3 }
            ui.assertEnabled(R.string.curation_selected, false)
            ui.assertEnabled(R.string.curation_summarize, false)
            onView(withText(R.string.curation_enable)).check(matches(not(isChecked())))
            ui.tap(R.string.curation_enable)
            ui.awaitUi("enabled") { it.preferences.enabled && it.operation == null }
            ui.tapMessage("$id-first")
            ui.awaitUi("manual source selected") { it.selectedMessageId == "$id-first" }
            ui.assertEnabled(R.string.curation_selected, true)
            ui.assertEnabled(R.string.curation_cloud, false)
            ui.tap(R.string.curation_enable)
            ui.awaitUi("disabled again") { !it.preferences.enabled && it.operation == null }
            ui.assertEnabled(R.string.curation_selected, false)
            ui.tap(R.string.curation_enable)
            ui.awaitUi("re-enabled") { it.preferences.enabled && it.operation == null }

            // Saving a memory excludes history, so inspect summary provenance before confirmation.
            ui.tap(R.string.curation_summarize)
            ui.awaitUi("local summary") { it.summaries.size == 1 && it.operation == null }
            val summary = ui.state().summaries.single()
            assertFalse(summary.cloudAllowed)
            assertEquals(setOf("$id-first", "$id-second", "$id-third"),
                ui.database.memoryCurationDao().summarySources(ui.owner, summary.id)
                    .map { it.sourceMessageId }.toSet())
            ui.tap(R.string.curation_all_sources)
            ui.awaitUi("all summary sources") { it.sources?.size == 3 && it.operation == null }
            listOf("first", "second", "third").forEach { ui.assertDialogContains("$id-$it") }
            listOf(P3CurationUiFixture.FIRST, P3CurationUiFixture.SECOND, P3CurationUiFixture.THIRD)
                .forEach(ui::assertDialogContains)
            ui.closeSources()

            ui.propose("$id-first")
            val first = ui.state().candidates.single()
            ui.capture(id, "candidate-list")
            assertTrue(ui.database.memoryDao().selectForPrompt(ui.owner, id, false,
                System.currentTimeMillis()).none { !it.id.startsWith("summary-") })
            ui.tap(R.string.curation_source)
            ui.awaitUi("candidate source") { it.sources?.singleOrNull()?.messageId == "$id-first" }
            ui.assertDialogContains(P3CurationUiFixture.FIRST)
            ui.closeSources()
            ui.tap(R.string.curation_edit)
            ui.awaitUi("candidate editor") { it.draft?.candidateId == first.id && it.conflictsLoaded }
            ui.edit(R.string.memory_content, "合成验收：偏好简短且分段的解释。")
            ui.tap(R.string.curation_permanent)
            val expiry = formatMemoryExpiry(System.currentTimeMillis() + 7 * 86_400_000L)
            ui.edit(R.string.curation_expiry, "2026-02-30")
            ui.awaitUi("invalid expiry captured") { it.draft?.expiryInput == "2026-02-30" }
            ui.assertEnabled(R.string.curation_confirm, false)
            ui.edit(R.string.curation_expiry, expiry)
            ui.tap(R.string.curation_create_new)
            ui.awaitUi("edited draft ready") { it.draft?.let { draft -> draft.valid && draft.resolutionChosen } == true }
            val draft = checkNotNull(ui.state().draft)
            ui.recreate()
            ui.awaitUi("draft retained after Activity.recreate") { it.draft == draft && it.conflictsLoaded }
            onView(withHint(R.string.memory_content)).perform(ui.centerInScroll())
                .check(matches(withText(draft.content)))
            onView(withHint(R.string.curation_expiry)).perform(ui.centerInScroll()).check(matches(withText(expiry)))
            onView(withText(R.string.curation_create_new)).check(matches(isChecked()))
            ui.capture(id, "candidate-editor")
            ui.tap(R.string.curation_confirm)
            ui.awaitUi("first memory committed") { it.draft == null && it.candidates.isEmpty() && it.operation == null }
            val accepted = checkNotNull(ui.database.memoryCurationDao().candidate(ui.owner, first.id))
            assertEquals("ACCEPTED", accepted.status)
            val memoryId = checkNotNull(accepted.memoryId)
            val saved = checkNotNull(ui.database.memoryDao().findOwned(memoryId, ui.owner))
            assertEquals(draft.content, saved.content)
            assertEquals(parseMemoryExpiry(expiry), saved.expiresAt)
            assertEquals("$id-first", saved.sourceMessageId)
            assertFalse(saved.cloudAllowed)
            assertEquals(1, saved.revision)

            ui.propose("$id-second")
            val second = ui.state().candidates.single()
            ui.tap(R.string.curation_edit)
            ui.awaitUi("replacement loaded") { it.conflictsLoaded && it.possibleConflicts.any { row -> row.id == memoryId } }
            ui.tapReplacement(memoryId)
            ui.edit(R.string.memory_content, "合成验收：现在偏好详细解释。")
            ui.awaitUi("revision one selected") { it.draft?.expectedRevision == 1 }
            // Competing writer is fixture preparation; the stale and successful writes use UI confirmation.
            assertEquals(1, ui.database.memoryDao().updateIfRevision(memoryId, ui.owner, 1, id,
                saved.type, "合成竞争写入", saved.sourceMessageId, System.currentTimeMillis(), saved.expiresAt, false))
            ui.tap(R.string.curation_confirm)
            ui.awaitUi("stale CAS rejected") { it.error == CurationError.OPERATION && it.operation == null }
            ui.assertVisible(R.string.curation_error_operation)
            assertEquals("合成验收：现在偏好详细解释。", ui.state().draft?.content)
            assertEquals(1, ui.state().draft?.expectedRevision)
            assertEquals("PENDING", ui.database.memoryCurationDao().candidate(ui.owner, second.id)?.status)
            assertEquals("合成竞争写入", ui.database.memoryDao().findOwned(memoryId, ui.owner)?.content)
            ui.tap(R.string.curation_retry_conflicts)
            ui.awaitUi("fresh revision loaded") { it.conflictsLoaded && it.operation == null &&
                it.possibleConflicts.any { row -> row.id == memoryId && row.revision == 2 } }
            ui.tapReplacement(memoryId)
            ui.edit(R.string.memory_content, "合成验收：明确更新为详细解释。")
            ui.tap(R.string.curation_confirm)
            ui.awaitUi("replacement committed") { it.draft == null && it.candidates.isEmpty() && it.operation == null }
            val updated = checkNotNull(ui.database.memoryDao().findOwned(memoryId, ui.owner))
            assertEquals(3, updated.revision)
            assertEquals("合成验收：明确更新为详细解释。", updated.content)
            assertEquals(memoryId, ui.database.memoryCurationDao().candidate(ui.owner, second.id)?.memoryId)
            assertEquals(1, ui.database.memoryDao().selectForPrompt(ui.owner, id, false,
                System.currentTimeMillis()).count { !it.id.startsWith("summary-") })

            ui.propose("$id-third")
            val ignoredId = ui.state().candidates.single().id
            ui.tap(R.string.curation_ignore)
            ui.awaitUi("candidate ignored") { it.candidates.isEmpty() && it.operation == null }
            val ignored = checkNotNull(ui.database.memoryCurationDao().candidate(ui.owner, ignoredId))
            assertEquals("IGNORED", ignored.status)
            assertEquals("", ignored.content)
            assertNull(ignored.memoryId)
            ui.propose("$id-third")
            ui.tap(R.string.curation_enable)
            ui.awaitUi("switch invalidated pending candidate") {
                !it.preferences.enabled && it.candidates.isEmpty() && it.operation == null
            }
            assertEquals("INVALID", ui.database.memoryCurationDao().candidate(ui.owner, ignoredId)?.status)
            ui.assertEnabled(R.string.curation_selected, false)
        } finally {
            ui.finish()
            try { ui.cleanup(id) } finally { ui.database.close() }
        }
    }
}

// Shared only by the two opt-in P3 classes; no androidTest Hilt component is required.
internal class P3CurationUiFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val owner = "OFFLINE_USER"

    init {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runP3Ui") == "true")
        check(context.packageName == "com.example.persona.validation") { "P3 requires the isolated validation APK" }
        check(instrumentation.context.packageName == "com.example.persona.validation.test")
        check(SessionTokenStore(context).read() == null) { "P3 fixtures require an offline validation session" }
    }

    val database = Room.databaseBuilder(context, AppDatabase::class.java, "persona_db_v2").build()
    var previousPreferences: MemoryPreferencesEntity? = null
    var preferenceCaptured = false
    private var activity: MemoryCurationActivity? = null
    private lateinit var model: MemoryCurationViewModel

    suspend fun prepare(id: String) {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM memory_candidates WHERE ownerId = ? AND status IN ('PENDING', 'RUNNING')",
            arrayOf(owner)
        ).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Finish or clean existing validation curation fixtures first" }
        }
        previousPreferences = database.memoryCurationDao().preferences(owner)
        preferenceCaptured = true
        database.memoryCurationDao().configure(MemoryPreferencesEntity(owner, false, false))
        database.personaDao().insertPersona(PersonaEntity(id, "P3 合成验收", "", "", "Synthetic fixture", owner, false))
        listOf("first" to FIRST, "second" to SECOND, "third" to THIRD).forEachIndexed { index, (suffix, text) ->
            database.messageDao().insertMessage(MessageEntity("$id-$suffix", id, owner, text, true,
                System.currentTimeMillis() + index, localOnly = true))
        }
    }

    fun launch(id: String) {
        val monitor = instrumentation.addMonitor(MemoryCurationActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MemoryCurationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MemoryActivity.EXTRA_PERSONA_ID, id)
                    .putExtra(MemoryActivity.EXTRA_PERSONA_NAME, "P3 合成验收"))
            }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000L)) as MemoryCurationActivity
            instrumentation.runOnMainSync {
                model = ViewModelProvider(checkNotNull(activity))[MemoryCurationViewModel::class.java]
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    suspend fun awaitUi(label: String, condition: (MemoryCurationUiState) -> Boolean) {
        try {
            withTimeout(20_000L) {
                var ready = false
                while (!ready) {
                    instrumentation.runOnMainSync { ready = condition(model.uiState.value) }
                    if (!ready) delay(50L)
                }
            }
            instrumentation.waitForIdleSync()
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val current = state()
            throw AssertionError("Timed out: $label; loading=${current.loading}, operation=${current.operation}, error=${current.error}", error)
        }
    }

    fun state(): MemoryCurationUiState {
        lateinit var state: MemoryCurationUiState
        instrumentation.runOnMainSync { state = model.uiState.value }
        return state
    }

    fun tap(resource: Int) = tap(withText(resource))
    private fun tap(matcher: Matcher<View>) {
        try {
            onView(matcher).perform(centerInScroll(), click())
        } catch (failure: Throwable) {
            capture("failure-${UUID.randomUUID()}", "screen")
            throw failure
        }
    }
    fun centerInScroll() = object : androidx.test.espresso.ViewAction {
        override fun getDescription() = "Center the control inside its ScrollView"
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun perform(controller: androidx.test.espresso.UiController, view: View) {
            view.rootView.findFocus()?.clearFocus()
            var parent = view.parent
            while (parent !is android.widget.ScrollView) parent = checkNotNull(parent?.parent)
            val scroll = parent
            val rectangle = android.graphics.Rect(0, 0, view.width, view.height)
            scroll.offsetDescendantRectToMyCoords(view, rectangle)
            scroll.scrollTo(0, rectangle.centerY() - scroll.height / 2)
            controller.loopMainThreadForAtLeast(300)
        }
    }
    fun assertVisible(resource: Int) {
        onView(withText(resource)).perform(centerInScroll()).check(matches(isDisplayed()))
    }
    fun tapMessage(id: String) = tap(withText(containsString("$id\n")))
    fun tapReplacement(id: String) = tap(withTagValue(org.hamcrest.Matchers.equalTo<Any>(id)))
    fun edit(hint: Int, text: String) {
        onView(withHint(hint)).perform(centerInScroll(), replaceText(text), closeSoftKeyboard())
    }
    fun assertEnabled(resource: Int, expected: Boolean) {
        onView(withText(resource)).check(matches(if (expected) isEnabled() else not(isEnabled())))
    }
    fun assertDialogContains(text: String) {
        onView(withId(android.R.id.message)).check(matches(withText(containsString(text))))
    }
    suspend fun closeSources() {
        onView(withText(R.string.memory_source_close)).perform(click())
        awaitUi("source dialog closed") { it.sources == null && it.operation == null }
    }
    suspend fun propose(sourceId: String) {
        tapMessage(sourceId)
        awaitUi("source selected") { it.selectedMessageId == sourceId && it.operation == null }
        tap(R.string.curation_selected)
        awaitUi("one manual candidate") { it.candidates.size == 1 && it.operation == null }
        assertEquals(sourceId, state().candidates.single().sourceMessageId)
    }

    fun recreate() {
        val old = checkNotNull(activity)
        val monitor = instrumentation.addMonitor(MemoryCurationActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { old.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000L)) as MemoryCurationActivity
            check(activity !== old)
            instrumentation.runOnMainSync {
                model = ViewModelProvider(checkNotNull(activity))[MemoryCurationViewModel::class.java]
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun capture(id: String, name: String) {
        instrumentation.waitForIdleSync()
        val directory = java.io.File(context.filesDir, "p3-ui-evidence/$id").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.FileOutputStream(java.io.File(directory, "$name.png")).use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }

    fun finish() {
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
        activity = null
    }

    suspend fun cleanup(id: String) {
        require(id.startsWith("p3-ui-") || id.startsWith("p3-recovery-"))
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM memory_candidates WHERE ownerId = ? AND personaId = ?", arrayOf(owner, id))
        database.memoryDao().deleteByPersona(owner, id)
        database.personaDao().deletePersona(id, owner)
        if (preferenceCaptured) {
            previousPreferences?.let { database.memoryCurationDao().configure(it) }
                ?: database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM memory_preferences WHERE ownerId = ?", arrayOf(owner))
        }
    }

    companion object {
        const val FIRST = "我偏好简短解释。"
        const val SECOND = "我偏好详细解释。"
        const val THIRD = "我的目标是每周阅读一本书。"
    }
}
