package com.example.persona.features

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.R
import com.example.persona.core.ai.SafTestDocumentsProvider
import com.example.persona.core.ai.telemetry.FileInferenceJournal
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.features.diagnostics.InferenceDiagnosticsActivity
import com.example.persona.features.diagnostics.InferenceDiagnosticsUiState
import com.example.persona.features.diagnostics.InferenceDiagnosticsViewModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.json.JSONArray
import org.json.JSONObject

// Uses the Activity's default Hilt factory. No androidTest Hilt component or replacement DI.
internal class P4DiagnosticsUiFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    init {
        assumeTrue("Opt in with runP4Diagnostics=true", arguments.getString("runP4Diagnostics") == "true")
        check(context.packageName == "com.example.persona.validation") { "Validation target only" }
        check(instrumentation.context.packageName == "com.example.persona.validation.test")
        assumeTrue("Intent observation uses API 26 ActivityMonitor", Build.VERSION.SDK_INT >= 26)
        check(SessionTokenStore(context).read() == null) { "Use an offline validation install" }
    }

    val runId = (arguments.getString("p4DiagnosticsRunId") ?: "p4-${UUID.randomUUID()}").also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
    }
    private val root = File(context.filesDir, "p4-diagnostics").also {
        check(it.canonicalFile == File(context.filesDir.canonicalFile, "p4-diagnostics"))
        check(it.isDirectory || it.mkdirs())
    }
    val evidence = File(root, runId).also {
        check(it.canonicalFile.parentFile == root.canonicalFile && !it.exists()) { "Run ID must be fresh" }
        check(it.mkdir())
    }
    val providerSource = File(evidence, "readonly-provider-source.json")
    private val stages = JSONArray()
    val observations = JSONObject().put("schemaVersion", 1).put("runId", runId)
        .put("targetPackage", context.packageName).put("sdk", Build.VERSION.SDK_INT)
        .put("status", "running").put("stages", stages)
        .put("successfulSafContentExport", "not_covered_read_only_debug_provider")
        .put("systemPickerSaveNavigation", "not_covered")
        .put("successfulOutputRoute", "blocked_CreateDocument_raw_registry_result_private_file_uri")
        .put("deviceExecution", "instrumentation_only_not_mac_grammar_check")
        .put("fixtureProviderEntry", "unique_run_id_retained_with_evidence_until_process_exit")
    private val requests = CopyOnWriteArrayList<Intent>()
    val launchCount: Int get() = requests.size
    private var observer: Instrumentation.ActivityMonitor? = null
    private var blocker: Instrumentation.ActivityMonitor? = null
    private var activity: InferenceDiagnosticsActivity? = null
    lateinit var model: InferenceDiagnosticsViewModel
        private set
    lateinit var journal: FileInferenceJournal
        private set
    var ownedHashes: Set<String> = emptySet()
    private val journalDirectory = File(context.filesDir, "inference-diagnostics")
    private var ownsJournal = false
    private var authority: String? = null

    fun prepare() {
        persist()
        check(journalDirectory.canonicalFile == File(context.filesDir.canonicalFile, journalDirectory.name))
        check(!journalDirectory.exists()) { "Existing validation journal is not fixture-owned; preserve it and use a fresh install" }
        ownsJournal = true
        observer = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) requests.add(Intent(intent))
                return null
            }
        }.also { instrumentation.addMonitor(it) }
    }

    fun launch() {
        val monitor = instrumentation.addMonitor(InferenceDiagnosticsActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, InferenceDiagnosticsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000L)) as InferenceDiagnosticsActivity
            bindModel()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun bindModel() {
        instrumentation.runOnMainSync {
            checkNotNull(activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(checkNotNull(activity))[InferenceDiagnosticsViewModel::class.java]
            // Observe the dependency actually supplied by Hilt, never construct another journal writer.
            val field = InferenceDiagnosticsViewModel::class.java.getDeclaredField("journal")
            field.isAccessible = true
            journal = field.get(model) as FileInferenceJournal
        }
    }

    fun recreate() {
        val old = checkNotNull(activity)
        val monitor = instrumentation.addMonitor(InferenceDiagnosticsActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { old.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000L)) as InferenceDiagnosticsActivity
            assertNotSame(old, activity)
            bindModel()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun state(): InferenceDiagnosticsUiState {
        lateinit var value: InferenceDiagnosticsUiState
        instrumentation.runOnMainSync { value = model.uiState.value }
        return value
    }

    suspend fun awaitState(label: String, predicate: (InferenceDiagnosticsUiState) -> Boolean) {
        observations.put("currentCheck", label)
        persist()
        try {
            withTimeout(20_000L) { while (!predicate(state())) delay(50) }
            instrumentation.waitForIdleSync()
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val current = state()
            throw AssertionError("Timeout: $label; total=${current.total}, busy=${current.busy}, " +
                "awaiting=${current.awaitingDocument}, error=${current.error}", error)
        }
    }

    suspend fun assertNoAdditionalLaunch(expected: Int) {
        instrumentation.waitForIdleSync()
        delay(500)
        assertEquals("Recreation must not launch another chooser", expected, launchCount)
        assertCreateDocument(requests.last())
    }

    private fun assertCreateDocument(intent: Intent) {
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        val expected = ActivityResultContracts.CreateDocument("application/json")
            .createIntent(context, "persona-inference-diagnostics.json")
        assertEquals(expected.categories, intent.categories)
        assertEquals("persona-inference-diagnostics.json", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    suspend fun awaitDocumentsWindow() {
        withTimeout(15_000L) {
            while (documentsWindow() == null) delay(100)
        }
        observations.put("systemPickerPackage", documentsWindow())
        persist()
    }

    private fun documentsWindow(): String? {
        val window = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return try {
            window.packageName?.toString()?.takeIf {
                it == "com.android.documentsui" || it == "com.google.android.documentsui"
            }
        } finally {
            @Suppress("DEPRECATION")
            window.recycle()
        }
    }

    fun cancelSystemPicker() {
        check(documentsWindow() != null) { "Refusing Back outside the checked DocumentsUI window" }
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
    }

    fun blockPicker() {
        check(blocker == null)
        // A blocking filter monitor with a null result suppresses launch without delivering a callback.
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("application/json")
        }
        blocker = instrumentation.addMonitor(filter, null, true)
    }

    suspend fun requestBlockedExport() {
        check(blocker != null)
        val before = launchCount
        tap(R.string.diagnostics_export)
        awaitState("blocked export pending") { !it.busy && it.awaitingDocument && it.exportData != null }
        assertEquals(before + 1, launchCount)
        assertCreateDocument(requests.last())
    }

    fun deliver(resultCode: Int, uri: Uri) {
        check(blocker != null)
        instrumentation.runOnMainSync {
            val registry = checkNotNull(activity).activityResultRegistry
            val saved = Bundle().also(registry::onSaveInstanceState)
            // Fail closed if AndroidX changes its saved-state schema or more than one launcher is pending.
            val keys = checkNotNull(saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"))
            val codes = checkNotNull(saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"))
            val launched = checkNotNull(saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS"))
            val pending = launched.distinct().single()
            observations.put("rawResultPendingCopies", launched.size)
            check(keys.size == codes.size && pending.startsWith("activity_rq#"))
            val index = keys.indexOf(pending)
            check(index >= 0)
            assertTrue(registry.dispatchResult(codes[index], resultCode, Intent().setData(uri)))
        }
    }

    fun tap(resource: Int) {
        onView(withText(resource)).perform(centerInScroll(), click())
    }

    private fun centerInScroll() = object : ViewAction {
        override fun getDescription() = "Center a diagnostics control before injecting a real tap"
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun perform(controller: UiController, view: View) {
            var parent = view.parent
            while (parent !is ScrollView) parent = checkNotNull(parent?.parent)
            val bounds = android.graphics.Rect(0, 0, view.width, view.height)
            parent.offsetDescendantRectToMyCoords(view, bounds)
            parent.scrollTo(0, bounds.centerY() - parent.height / 2)
            controller.loopMainThreadForAtLeast(300)
        }
    }

    fun clearThroughDialog(confirm: Boolean) {
        tap(R.string.diagnostics_clear)
        onView(withId(android.R.id.message)).check(matches(withText(R.string.diagnostics_clear_confirm)))
        onView(withId(if (confirm) android.R.id.button1 else android.R.id.button2)).perform(click())
    }

    fun assertCounts(total: Int, completed: Int, failed: Int, cancelled: Int, other: Int) {
        onView(withText(context.getString(R.string.diagnostics_counts, total, completed, failed,
            cancelled, other))).check(matches(isDisplayed()))
    }

    fun assertText(resource: Int) {
        onView(withText(resource)).perform(centerInScroll()).check(matches(isDisplayed()))
    }

    fun assertExportEnabled() {
        onView(withText(R.string.diagnostics_export)).check(matches(isEnabled()))
    }

    @Suppress("DEPRECATION")
    fun registerReadOnlyDocument(): Uri {
        val provider = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS).providers.orEmpty().single {
                it.name == SafTestDocumentsProvider::class.java.name
            }
        check(provider.enabled && provider.authority == "${context.packageName}.p1saf")
        authority = provider.authority
        val id = "p4-diagnostics-$runId"
        providerSource.writeText("synthetic-readonly-sentinel")
        ParcelFileDescriptor.open(providerSource, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val data = Bundle().apply {
                putString("name", "$id.json")
                putString("parent", "root")
                putLong("size", providerSource.length())
                putParcelable("descriptor", descriptor)
            }
            checkNotNull(context.contentResolver.call(Uri.parse("content://${provider.authority}"),
                "persona.register", id, data))
        }
        // Do not reset this process-wide provider: other validation fixtures may own entries.
        // Its duplicated descriptor references retained evidence and closes with the process.
        return DocumentsContract.buildDocumentUri(provider.authority, id).also {
            context.contentResolver.query(it, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null).use { cursor ->
                check(cursor != null && cursor.moveToFirst() && cursor.getString(0) == id)
            }
        }
    }

    fun providerOpens(): Int = checkNotNull(context.contentResolver.call(
        Uri.parse("content://${checkNotNull(authority)}"), "persona.stats", null, null)).getInt("opens")

    fun finishActivity() {
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
        activity = null
    }

    fun observed(name: String, status: String, detail: String) {
        stages.put(JSONObject().put("name", name).put("status", status).put("detail", detail))
        persist()
    }

    private fun persist() {
        File(evidence, "observations.json").writeText(observations.toString(2), Charsets.UTF_8)
    }

    fun complete(failure: Throwable?) {
        var cleanupFailure: Throwable? = null
        fun clean(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
        }
        clean {
            if (blocker == null && activity != null && ::model.isInitialized &&
                state().awaitingDocument && launchCount > 0 && documentsWindow() != null) {
                cancelSystemPicker()
                instrumentation.waitForIdleSync()
            }
        }
        clean { finishActivity() }
        clean { blocker?.let(instrumentation::removeMonitor) }
        clean { observer?.let(instrumentation::removeMonitor) }
        clean {
            check(context.packageName == "com.example.persona.validation")
            if (ownsJournal && journalDirectory.exists()) {
                check(journalDirectory.canonicalFile == File(context.filesDir.canonicalFile, journalDirectory.name))
                check(::journal.isInitialized) { "Unobserved journal retained for inspection" }
                val rows = runBlocking { journal.snapshot() }
                check(rows.all { it.requestHash in ownedHashes }) { "Foreign journal records retained" }
                val files = journalDirectory.listFiles().orEmpty()
                check(files.all { it.name == "inference-attempts.json" && it.isFile &&
                    it.canonicalFile.parentFile == journalDirectory.canonicalFile })
                files.forEach { check(it.delete()) }
                check(journalDirectory.delete())
            }
        }
        observations.put("cleanup", if (cleanupFailure == null) "passed" else "failed_data_retained")
        observations.put("status", if (failure == null && cleanupFailure == null) "passed_with_limits" else "failed")
        (failure ?: cleanupFailure)?.let { observations.put("failureType", it.javaClass.name) }
        clean { persist() }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "P4_DIAGNOSTICS_EVIDENCE: files/p4-diagnostics/$runId/observations.json\n")
        })
        cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
