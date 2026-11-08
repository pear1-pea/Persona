package com.example.persona.features

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.view.WindowManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.*
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.ai.telemetry.*
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.memory.MemoryExtraction
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.*
import com.example.persona.data.remote.dto.*
import com.example.persona.data.repository.*
import com.example.persona.di.DatabaseModule
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Shortflow: install private primary -> real extraction/optional SSE -> real preemption/chat -> export/cleanup.
// Build: -Ppersona.isolatedValidation=true. Args: runP4Runtime=true, p4RunId, p4SourceRevision.
@RunWith(AndroidJUnit4::class)
class P4RuntimeTelemetryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val gson = Gson()
    @Test(timeout = 600_000L)
    fun realMnnExtractionTelemetryAndForegroundPreemption() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runP4Runtime=true", args.getString("runP4Runtime") == "true")
        check(context.packageName == "com.example.persona.validation")
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val runId = requireNotNull(args.getString("p4RunId"))
        require(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val revision = requireNotNull(args.getString("p4SourceRevision"))
        require(revision.matches(Regex("(?:[0-9a-fA-F]{7,64}|sha256:[0-9a-fA-F]{64})")))
        val root = File(context.filesDir, "p4-runtime/$runId").canonicalFile
        check(root.parentFile == File(context.filesDir, "p4-runtime").canonicalFile)
        check(!root.exists()) { "Use a fresh P4 run ID" }
        check(root.mkdirs() && root.absoluteFile == root.canonicalFile)
        val fixture = File(root, "fixture").apply { check(mkdir()) }
        val name = "p4-runtime-$runId"
        check(!context.getDatabasePath(name).exists())
        val prefs = mutableSetOf<String>()
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                check(key in setOf("persona_session", "local_model_settings"))
                return super.getSharedPreferences("$name-$key".also { prefs += it }, mode)
            }
            override fun getExternalFilesDir(type: String?): File {
                check(type == "models")
                return File(fixture, "models").apply { check(isDirectory || mkdirs()) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val journal = FileInferenceJournal(File(fixture, "privatejournal"))
        val telemetry = BufferedInferenceTelemetry(journal, scope)
        val telemetryWorker = scope.coroutineContext.job.children.single()
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(scoped, validator, installer)
        val native = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        // Observe genuine output in memory only; forward every event unchanged to the real parser.
        val outputs = ConcurrentHashMap<String, StringBuffer>()
        val engine = P3NativeRecoveryEngine(object : LocalAiEngine by native {
            override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
                params: GenerationParams): Flow<GenerationEvent> = native.streamResponse(session, prompt, history, params)
                .onEach { if (it is GenerationEvent.Delta) outputs.getOrPut(session.id) { StringBuffer() }.append(it.text) }
        })
        val db = Room.databaseBuilder(context, com.example.persona.data.local.AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val authApi = Proxy.newProxyInstance(AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)) { _, method, _ ->
            check(method.name == "login")
            AuthSessionDto("p4-private-token", "2099-01-01T00:00:00Z", AuthUserDto(OWNER, "p4@example.invalid"))
        } as AuthApi
        val auth = AuthManager(authApi, SessionTokenStore(scoped), db.personaDao())
        val cloudCalls = AtomicInteger()
        val cloud = CloudChatRepository(object : CloudChatApi {
            override fun streamChat(request: ChatRequest, authorization: String?): Call<ResponseBody> {
                check(authorization == "Bearer p4-private-token")
                check(cloudCalls.incrementAndGet() == 1) { "At most one bounded upgrade" }
                check(request.messages.last().content == MemoryExtraction.prompt(SOURCE))
                check(request.messages.size == 2 && request.maxTokens == 256 && request.temperature == 0f)
                return P4RuntimeTelemetrySseCall(VALID)
            }
        }, BackendConfig("https://p4-runtime.invalid/", "", true), auth)
        val hybrid = HybridAiRepository(cloud, engine, manager, HybridRouter(),
            { NetworkState.UNMETERED }, scope, telemetry = telemetry)
        val curator = RoomMemoryCurationRepository(db, auth, RoomMemoryRepository(db.memoryDao(), auth), hybrid)
        val report = linkedMapOf<String, Any?>("run_id" to runId, "source_revision" to revision,
            "synthetic_inputs_only" to true, "status" to "running", "quality_status" to "not_evaluated",
            "model_source" to "files/model-sources/primary", "routing_context" to "controlled_UNMETERED_no_socket",
            "actual_network" to RoutingContextProvider(context).networkState().name,
            "extraction_params" to EXTRACT, "foreground_params" to CHAT,
            "resource_gate" to "battery>=20; thermal<SEVERE; powerSave=false; wait<=180s")
        var host: android.app.Activity? = null
        var pending: Job? = null
        val started = SystemClock.elapsedRealtime()
        fun checkpoint() = write(File(root, "observations.json"), gson.toJson(report))
        try {
            checkpoint()
            host = instrumentation.startActivitySync(Intent(context, CreatePersonaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { checkNotNull(host).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            suspend fun resources(stage: String) {
                val start = SystemClock.elapsedRealtime()
                withTimeout(180_000L) {
                    while (true) {
                        val p = manager.deviceCapability()
                        if (p.batteryPercent >= 20 && !p.powerSaveMode && p.thermalStatus < ThermalStatus.SEVERE) break
                        delay(2_000L)
                    }
                }
                val p = manager.deviceCapability()
                check(p.batteryPercent >= 20 && !p.powerSaveMode && p.thermalStatus < ThermalStatus.SEVERE)
                report[stage] = mapOf("wait_ms" to SystemClock.elapsedRealtime() - start,
                    "battery" to p.batteryPercent, "thermal" to p.thermalStatus.name, "power_save" to p.powerSaveMode,
                    "native_heap_bytes" to Debug.getNativeHeapAllocatedSize())
                checkpoint()
            }
            resources("before_install")
            val source = File(context.filesDir, "model-sources/primary").canonicalFile
            check(source.isDirectory && source.absoluteFile == source.canonicalFile)
            source.walkTopDown().forEach { check(!AndroidSymlinkChecker.isSymbolicLink(it)) }
            val manifestHash = sha(File(source, "manifest.json").readBytes())
            check(manager.refreshInstalledModels().isEmpty())
            val installed = installer.installFromDirectory(source, manager.modelsRoot(), manager.deviceCapability())
            check(installed is ModelPackageInstallResult.Success) {
                (installed as? ModelPackageInstallResult.Failure)?.reason ?: "Unexpected install result"
            }
            val model = installed.model
            check(model.id.lowercase().contains("0.5b") && model.backend == Backend.MNN && model.family == "QWEN2_5")
            check(sha(File(model.modelDir, "manifest.json").readBytes()) == manifestHash)
            report["manifest_sha256"] = manifestHash
            report["model_id"] = model.id
            report["model_version"] = model.version
            check(hybrid.selectLocalModel(model.modelDir) != null && hybrid.initializeLocalModel())
            auth.signIn("p4@example.invalid", "synthetic-password")
            db.personaDao().insertPersona(PersonaEntity(PERSONA, "P4 synthetic", "", "", "", OWNER))
            for (id in listOf("extract", "preempt")) db.messageDao().insertMessage(
                MessageEntity(id, PERSONA, OWNER, SOURCE, true, System.currentTimeMillis(), localOnly = false))
            curator.configure(true, true)
            resources("before_extract")
            val extractionStart = SystemClock.elapsedRealtime()
            curator.proposeWithModel(PERSONA, "extract", cloud = false, allowQualityUpgrade = true)
            report["extraction_ms"] = SystemClock.elapsedRealtime() - extractionStart
            val local = engine.calls.single()
            check(local.params == EXTRACT && local.terminal == "Completed")
            val raw = checkNotNull(outputs[local.sessionId]).toString()
            val localAccepted = try { MemoryExtraction.decode(raw, SOURCE); true } catch (_: IllegalArgumentException) { false }
            check(cloudCalls.get() == if (localAccepted) 0 else 1)
            val rows = awaitRows(journal, if (localAccepted) 1 else 2, contracts = true)
            check(rows.map { it.attempt } == (1..rows.size).toList())
            check(rows.all { it.task == AiTaskKind.MEMORY_EXTRACTION && it.outcome == AttemptOutcome.COMPLETED })
            check(rows.map { it.requestHash }.distinct() == listOf(sha(local.sessionId.toByteArray())))
            check(rows[0].backend == Backend.MNN && rows[0].trigger == AttemptTrigger.INITIAL)
            check(rows[0].modelFingerprint == sha("${model.id}@${model.version}".toByteArray()))
            check(rows[0].contractStatus == if (localAccepted) ContractStatus.ACCEPTED else ContractStatus.REJECTED)
            rows.getOrNull(1)?.let { check(it.backend == Backend.CLOUD && it.modelFingerprint == null &&
                it.trigger == AttemptTrigger.CONTRACT_FALLBACK && it.contractStatus == ContractStatus.ACCEPTED) }
            val candidates = curator.observe(PERSONA).first()
            val expected = MemoryExtraction.decode(if (localAccepted) raw else VALID, SOURCE)
            check(candidates.map { it.content }.sorted() == expected.map { it.content }.sorted())
            check(candidates.all { it.status == "PENDING" && it.memoryId == null && SOURCE.contains(it.content) })
            report["branch"] = if (localAccepted) "local_contract_accepted_no_upgrade" else "local_contract_rejected_one_sse_upgrade"
            report["pending_candidates"] = candidates.size
            checkpoint()
            resources("before_preempt")
            val gate = P3NativeRecoveryEngine.Gate()
            engine.gate = gate
            val result = CompletableDeferred<Throwable?>()
            val background = scope.launch {
                try {
                    curator.proposeWithModel(PERSONA, "preempt", cloud = false, allowQualityUpgrade = true)
                    result.complete(null)
                } catch (error: Throwable) {
                    result.complete(error)
                    if (error is CancellationException) throw error
                }
            }
            pending = background
            val displaced = withTimeout(50_000L) {
                select {
                    gate.firstDelta.onAwait { it }
                    result.onAwait { throw AssertionError("Extraction ended before first real Delta: ${it?.javaClass?.simpleName}") }
                }
            }
            check(background.isActive && displaced.stopReason == null)
            engine.gate = null
            val session = GenerationSession().apply { bindRoutingDecision(checkNotNull(displaced.routingDecision)) }
            var deltas = 0
            var completed = false
            val chatStart = SystemClock.elapsedRealtime()
            withTimeout(90_000L) {
                hybrid.streamResponse(HybridAiRepository.Mode.LOCAL, session, "Answer briefly.", "Reply hello.",
                    route = Route.LOCAL, params = CHAT).collect { event ->
                    check(!completed)
                    when (event) {
                        is GenerationEvent.Delta -> deltas++
                        is GenerationEvent.Completed -> { check(event.metrics.outputTokens > 0); completed = true }
                        else -> error("Foreground native generation did not complete")
                    }
                }
                background.join()
                gate.cancelled.await()
            }
            check(completed && deltas > 0 && background.isCancelled && result.await() is CancellationException)
            check(displaced.stopReason == StopReason.SESSION_REPLACED && engine.state.value == EngineState.Ready)
            check(engine.calls.size == 3 && engine.calls[1].params == EXTRACT && engine.calls[2].params == CHAT)
            check(engine.calls[2].terminal == "Completed")
            val all = awaitRows(journal, rows.size + 2)
            val cancelled = all.single { it.requestHash == sha(displaced.id.toByteArray()) }
            check(cancelled.attempt == 1 && cancelled.backend == Backend.MNN && cancelled.outcome == AttemptOutcome.CANCELLED)
            check(cancelled.contractStatus == ContractStatus.NOT_EVALUATED && cancelled.trigger == AttemptTrigger.INITIAL &&
                cancelled.stopReason == StopReason.SESSION_REPLACED)
            val chat = all.single { it.requestHash == sha(session.id.toByteArray()) }
            check(chat.task == AiTaskKind.CHAT && chat.backend == Backend.MNN && chat.outcome == AttemptOutcome.COMPLETED &&
                chat.contractStatus == ContractStatus.NOT_EVALUATED && chat.trigger == AttemptTrigger.INITIAL)
            check(curator.observe(PERSONA).first().map { it.id } == candidates.map { it.id })
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memories").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
            db.openHelper.readableDatabase.query("SELECT status FROM memory_candidates WHERE sourceMessageId='preempt'").use {
                check(it.moveToFirst() && it.getString(0) == "FAILED" && !it.moveToNext())
            }
            check(cloudCalls.get() == if (localAccepted) 0 else 1)
            check(telemetry.droppedCommands == 0L && telemetry.failedCommands == 0L)
            check(sha(File(source, "manifest.json").readBytes()) == manifestHash)
            val exported = journal.exportJson()
            check(!exported.contains(SOURCE) && !exported.contains(CONTENT) && !exported.contains("SOURCE_JSON"))
            outputs.values.forEach { output -> check(!exported.contains(gson.toJson(output.toString()))) }
            check(all.all { it.modelFingerprint == null || it.modelFingerprint == rows[0].modelFingerprint })
            report["preemption"] = "real_first_delta_cancelled_no_cloud_continuation"
            report["foreground_ms"] = SystemClock.elapsedRealtime() - chatStart
            report["status"] = "passed"
        } catch (error: Throwable) {
            report["status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    pending?.cancelAndJoin()
                    telemetry.close()
                    // Join the journal writer before exporting; the other long-lived child watches the model.
                    withTimeout(10_000L) { telemetryWorker.join() }
                    write(File(root, "journal-export.json"), journal.exportJson())
                } finally {
                    try {
                        scope.coroutineContext.job.cancelAndJoin()
                        hybrid.clearLocalModel()
                        native.release()
                        db.close()
                        if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
                        prefs.forEach { check(context.deleteSharedPreferences(it)) }
                        fixture.walkTopDown().forEach { check(!AndroidSymlinkChecker.isSymbolicLink(it)) }
                        check(fixture.canonicalFile.parentFile == root.canonicalFile && fixture.deleteRecursively())
                        report["fixture_cleanup"] = true
                    } catch (error: Throwable) {
                        report["status"] = "cleanup_failed"
                        throw error
                    } finally {
                        report["duration_ms"] = SystemClock.elapsedRealtime() - started
                        report["cloud_attempts"] = cloudCalls.get()
                        report["engine_calls"] = engine.calls
                        report["telemetry_dropped"] = telemetry.droppedCommands
                        report["telemetry_failed"] = telemetry.failedCommands
                        checkpoint()
                        host?.let { activity -> instrumentation.runOnMainSync {
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            activity.finish()
                        } }
                    }
                }
            }
        }
    }

    private suspend fun awaitRows(journal: FileInferenceJournal, count: Int, contracts: Boolean = false): List<InferenceAttempt> =
        withTimeout(10_000L) {
            var rows: List<InferenceAttempt>
            do {
                rows = journal.snapshot()
                if (rows.size == count && (!contracts || rows.all { it.contractStatus != ContractStatus.NOT_EVALUATED })) break
                check(rows.size <= count)
                delay(25L)
            } while (true)
            rows
        }
    private fun write(file: File, json: String) = FileOutputStream(file).use {
        it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val OWNER = "p4-runtime-owner"
        private const val PERSONA = "p4-runtime-persona"
        private const val SOURCE = "请记住：我喜欢喝桂花茶。"
        private const val CONTENT = "我喜欢喝桂花茶。"
        private const val VALID = """{"suggestions":[{"type":"PREFERENCE","content":"我喜欢喝桂花茶。"}]}"""
        private val EXTRACT = GenerationParams(temperature = 0f, maxTokens = 256)
        private val CHAT = GenerationParams(temperature = 0f, maxTokens = 8)
    }
}

private class P4RuntimeTelemetrySseCall(private val content: String) : Call<ResponseBody> {
    private val cancelled = AtomicBoolean()
    private val executed = AtomicBoolean()
    override fun enqueue(callback: Callback<ResponseBody>) { callback.onResponse(this, execute()) }
    override fun execute(): Response<ResponseBody> {
        check(executed.compareAndSet(false, true) && !cancelled.get())
        val chunk = Gson().toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to content)))))
        return Response.success("data: $chunk\n\ndata: [DONE]\n\n".toResponseBody())
    }
    override fun clone(): Call<ResponseBody> = P4RuntimeTelemetrySseCall(content)
    override fun isExecuted() = executed.get()
    override fun isCanceled() = cancelled.get()
    override fun cancel() { cancelled.set(true) }
    override fun request() = Request.Builder().url("https://p4-runtime.invalid/").build()
    override fun timeout() = Timeout.NONE
}
