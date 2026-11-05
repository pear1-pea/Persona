package com.example.persona.features

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.*
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomMemoryCurationRepository
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Build on P3 sources with -Ppersona.isolatedValidation=true; never use the original app.
// Host: -e runP3Native true -e p3NativeRunId <id> -e p3NativePhase prepare|verify
// Verify also requires -e p3ForceStoppedPid <READY pid>, after force-stopping validation.
@RunWith(AndroidJUnit4::class)
class P3NativeRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 1_200_000L)
    fun prepareOrVerifyRealExtractionAfterHostForceStop() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runP3Native=true", args.getString("runP3Native") == "true")
        check(context.packageName == "com.example.persona.validation") { "Strict validation-only test" }
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val phase = args.getString("p3NativePhase")
        check(phase == "prepare" || phase == "verify")
        val runId = requireNotNull(args.getString("p3NativeRunId"))
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        // A host deadline also bounds JNI calls that do not cooperate with coroutine cancellation.
        val worker = Executors.newSingleThreadExecutor { Thread(it, "P3-native-recovery").apply { isDaemon = true } }
        val future = worker.submit(Callable {
            runBlocking {
                val fixture = P3NativeRecoveryFixture(context, runId)
                if (phase == "prepare") prepare(fixture) else verify(fixture)
            }
        })
        try {
            future.get(1_140_000L, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            throw AssertionError("P3 native phase exceeded its wall-clock deadline; retain failure evidence", error)
        } finally {
            future.cancel(true)
            worker.shutdownNow()
        }
    }

    private suspend fun prepare(f: P3NativeRecoveryFixture) {
        check(!f.checkpoint.exists() && !f.observations.exists()) { "Use a fresh run ID, or verify the existing fixture" }
        check(!context.getDatabasePath(f.name).exists())
        val sourceRoot = File(context.filesDir, "model-sources").canonicalFile
        check(sourceRoot.parentFile == context.filesDir.canonicalFile)
        val source = File(sourceRoot, "secondary")
        check(source.canonicalFile.parentFile == sourceRoot && source.isDirectory)
        check(!AndroidSymlinkChecker.isSymbolicLink(source))
        val native = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val engine = P3NativeRecoveryEngine(native)
        val hybrid = f.hybrid(engine)
        val report = report(f, "prepare")
        report["engine_calls"] = engine.calls
        report["engine_lifecycle"] = engine.lifecycle
        var modelDir: String? = null
        var retainForHost = false
        val host = foregroundHost()
        try {
            f.awaitResources()
            check(f.manager.refreshInstalledModels().isEmpty()) { "Requires an empty private fixture catalog" }
            val installed = f.installer.installFromDirectory(source, f.manager.modelsRoot(), f.manager.deviceCapability())
            check(installed is ModelPackageInstallResult.Success) { "Real MNN package validation failed" }
            val modelPath = installed.model.modelDir
            modelDir = modelPath
            f.checkedModel(modelPath)
            check(installed.model.id.lowercase().contains("1.5b")) { "Secondary must be the supplied 1.5B model" }
            report["model_id"] = installed.model.id
            report["model_version"] = installed.model.version
            report["manifest_sha256"] = MessageDigest.getInstance("SHA-256").digest(File(source, "manifest.json").readBytes())
                .joinToString("") { "%02x".format(it) }
            report["stage"] = "initialize_real_native"
            f.sync(f.observations, report)
            assertNotNull(hybrid.selectLocalModel(modelPath))
            assertTrue("Real initialization and smoke test", hybrid.initializeLocalModel())
            val curator = f.open(hybrid)
            val db = checkNotNull(f.database)
            db.personaDao().insertPersona(PersonaEntity(PERSONA, "P3 synthetic fixture", "", "", "", P3NativeRecoveryFixture.OWNER))
            for (id in listOf(PREEMPT_SOURCE, DEATH_SOURCE)) {
                db.messageDao().insertMessage(MessageEntity(id, PERSONA, P3NativeRecoveryFixture.OWNER,
                    "请记住：我喜欢喝桂花茶。", true, System.currentTimeMillis(), localOnly = true))
            }
            curator.configure(true, false)
            preempt(f, engine, hybrid, curator, report)
            f.awaitResources()
            val gate = P3NativeRecoveryEngine.Gate()
            engine.gate = gate
            val extraction = startExtraction(f, curator, DEATH_SOURCE)
            val session = firstDelta(gate, extraction.second)
            assertTrue(extraction.first.isActive)
            assertNull(session.stopReason)
            assertEquals(3, engine.calls.size)
            val running = f.tasks().single { it.sourceMessageId == DEATH_SOURCE }
            assertEquals("RUNNING", running.status)
            assertTrue(engine.calls.last().deltas > 0)
            f.assertNoMemories()
            val consent = checkNotNull(db.memoryCurationDao().preferences(P3NativeRecoveryFixture.OWNER))
            val state = Checkpoint(f.runId, f.name, modelPath, running.id,
                f.tasks().single { it.sourceMessageId == PREEMPT_SOURCE }.id,
                Process.myPid(), PROCESS_TOKEN, consent.revision)
            report["stage"] = "awaiting_host_force_stop"
            report["status"] = "prepared_requires_host_force_stop"
            report["prepared_pid"] = state.oldPid
            report["native_extraction_started"] = true
            report["cloud_attempts"] = f.networkCalls.get()
            f.sync(f.observations, report)
            f.sync(f.checkpoint, state)
            retainForHost = true
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "P3_NATIVE_RECOVERY_READY pid=${state.oldPid} runId=${f.runId}\n")
                putInt("p3PreparedPid", state.oldPid)
            })
            // Stay within the production extraction's 60-second timeout after its first delta.
            withTimeoutOrNull(45_000L) { extraction.second.await() }
            retainForHost = false
            error("Host did not force-stop the live validation process within 45 seconds")
        } catch (error: Throwable) {
            retainForHost = false
            report["status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            throw error
        } finally {
            if (!retainForHost) {
                withContext(NonCancellable) {
                    try {
                        withTimeout(30_000L) { f.scope.coroutineContext[Job]?.cancelAndJoin() }
                        hybrid.clearLocalModel()
                        modelDir?.let { if (File(it).exists()) check(hybrid.deleteLocalModel(it)) }
                        f.cleanupStorage()
                        report["fixture_cleanup"] = true
                    } finally {
                        report["cloud_attempts"] = f.networkCalls.get()
                        f.sync(f.observations, report)
                        finishHost(host)
                    }
                }
            }
        }
    }

    private suspend fun preempt(f: P3NativeRecoveryFixture, engine: P3NativeRecoveryEngine,
        hybrid: HybridAiRepository, curator: RoomMemoryCurationRepository, report: MutableMap<String, Any?>) {
        val gate = P3NativeRecoveryEngine.Gate()
        engine.gate = gate
        val background = startExtraction(f, curator, PREEMPT_SOURCE)
        val displacedSession = firstDelta(gate, background.second)
        assertTrue(background.first.isActive)
        assertEquals("RUNNING", f.tasks().single().status)
        assertEquals(1, engine.calls.size)
        engine.gate = null
        val decision = checkNotNull(displacedSession.routingDecision)
        assertTrue(decision.isExecutable)
        assertEquals(Route.LOCAL, decision.route)
        assertFalse(decision.cloudAllowed)
        val session = GenerationSession().apply { bindRoutingDecision(decision) }
        var deltas = 0
        var completed = 0
        var terminal = false
        withTimeout(90_000L) {
            // Only the foreground request triggers cancellation; the test never cancels background here.
            hybrid.streamResponse(HybridAiRepository.Mode.LOCAL, session, "Answer briefly.",
                "Reply hello.", route = Route.LOCAL, params = CHAT_PARAMS).collect { event ->
                assertFalse("No events after terminal", terminal)
                when (event) {
                    is GenerationEvent.Delta -> { assertTrue(event.text.isNotEmpty()); deltas++ }
                    is GenerationEvent.Completed -> {
                        terminal = true
                        completed++
                        assertTrue(event.metrics.outputTokens > 0)
                    }
                    is GenerationEvent.Failed -> error("Foreground native generation failed: ${event.error.type}")
                    is GenerationEvent.Stopped -> error("Foreground native generation stopped: ${event.reason}")
                }
            }
            background.first.join()
            gate.cancelled.await()
        }
        assertTrue("Priority gate must cancel the actual background job", background.first.isCancelled)
        assertTrue(background.second.await() is CancellationException)
        assertEquals(StopReason.SESSION_REPLACED, displacedSession.stopReason)
        assertTrue(deltas > 0)
        assertEquals(1, completed)
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(2, engine.calls.size)
        assertEquals(EXTRACT_PARAMS, engine.calls[0].params)
        assertEquals(CHAT_PARAMS, engine.calls[1].params)
        assertEquals("Completed", engine.calls[1].terminal)
        val task = f.tasks().single()
        assertEquals("FAILED", task.status)
        assertEquals("", task.content)
        assertNull(task.memoryId)
        assertTrue(withTimeout(5_000L) { curator.observe(PERSONA).first() }.isEmpty())
        f.assertNoMemories()
        assertEquals(0, f.networkCalls.get())
        report["preemption"] = "passed"
        report["background_job_cancelled"] = true
        report["foreground_native_completed"] = true
        f.sync(f.observations, report)
    }

    private suspend fun verify(f: P3NativeRecoveryFixture) {
        check(f.checkpoint.isFile && f.observations.isFile) { "Prepare, then host force-stop validation first" }
        val state = Gson().fromJson(f.checkpoint.readText(), Checkpoint::class.java)
        check(state.runId == f.runId && state.databaseName == f.name)
        check(state.oldPid == InstrumentationRegistry.getArguments().getString("p3ForceStoppedPid")?.toIntOrNull())
        check(state.processToken != PROCESS_TOKEN && state.oldPid != Process.myPid())
        f.assertOldProcessDead(state.oldPid)
        f.checkedModel(state.modelDir)
        check(context.getDatabasePath(state.databaseName).isFile) { "Do not create an empty replacement DB" }
        val report = report(f, "verify")
        report["prepare_observations"] = Gson().fromJson(f.observations.readText(), JsonObject::class.java)
        val noop = P3NativeRecoveryNoopEngine()
        val hybrid = f.hybrid(noop)
        val host = foregroundHost()
        try {
            val curator = f.open(hybrid)
            val db = checkNotNull(f.database)
            val dao = db.memoryCurationDao()
            val before = checkNotNull(dao.candidate(P3NativeRecoveryFixture.OWNER, state.runningId))
            assertEquals("RUNNING", before.status)
            assertEquals(DEATH_SOURCE, before.sourceMessageId)
            assertEquals(PERSONA, before.personaId)
            assertEquals(2, f.tasks().size)
            f.assertNoMemories()
            assertEquals(0, noop.executionCalls.get())
            val preferences = withTimeout(10_000L) { curator.preferences() }
            assertTrue(preferences.enabled)
            assertFalse(preferences.cloudExtractionAllowed)
            assertEquals(state.consentRevision, dao.preferences(P3NativeRecoveryFixture.OWNER)?.revision)
            val recovered = checkNotNull(dao.candidate(P3NativeRecoveryFixture.OWNER, state.runningId))
            assertEquals("FAILED", recovered.status)
            assertEquals("", recovered.content)
            assertNull(recovered.memoryId)
            val preempted = checkNotNull(dao.candidate(P3NativeRecoveryFixture.OWNER, state.preemptedId))
            assertEquals("FAILED", preempted.status)
            assertEquals("", preempted.content)
            assertNull(preempted.memoryId)
            // Exercise the actual recovery entry twice and leave time for unintended asynchronous restarts.
            curator.preferences()
            delay(1_500L)
            assertTrue(withTimeout(5_000L) { curator.observe(PERSONA).first() }.isEmpty())
            assertEquals(2, f.tasks().size)
            assertTrue(f.tasks().all { it.status == "FAILED" && it.content.isEmpty() && it.memoryId == null })
            f.assertNoMemories()
            assertEquals("Recovery must not execute any model", 0, noop.executionCalls.get())
            assertEquals(0, f.networkCalls.get())
            report["old_pid"] = state.oldPid
            report["verified_pid"] = Process.myPid()
            report["recovery"] = "passed"
            report["running_to_failed"] = true
            report["content_cleared"] = true
            report["automatic_model_calls"] = noop.executionCalls.get()
            report["pending_candidates"] = 0
            report["formal_memories"] = 0
            report["status"] = "passed"
        } catch (error: Throwable) {
            report["status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(10_000L) { f.scope.coroutineContext[Job]?.cancelAndJoin() }
                    // No native runtime exists in verify. Delete only after ESRCH and private-root checks.
                    f.deleteDeadProcessModel(state.modelDir, state.oldPid)
                    f.cleanupStorage()
                    report["fixture_cleanup"] = true
                } catch (error: Throwable) {
                    report["status"] = "failed"
                    report["cleanup_failure_class"] = error.javaClass.simpleName
                    throw error
                } finally {
                    report["automatic_model_calls"] = noop.executionCalls.get()
                    report["cloud_attempts"] = f.networkCalls.get()
                    f.sync(f.observations, report)
                    finishHost(host)
                }
            }
        }
    }

    private fun startExtraction(f: P3NativeRecoveryFixture, curator: RoomMemoryCurationRepository,
        source: String): Pair<Job, CompletableDeferred<Throwable?>> {
        val result = CompletableDeferred<Throwable?>()
        val job = f.scope.launch {
            try {
                curator.proposeWithModel(PERSONA, source, false)
                result.complete(null)
            } catch (error: Throwable) {
                result.complete(error)
                if (error is CancellationException) throw error
            }
        }
        return job to result
    }

    private suspend fun firstDelta(gate: P3NativeRecoveryEngine.Gate,
        result: CompletableDeferred<Throwable?>): GenerationSession = withTimeout(50_000L) {
        select {
            gate.firstDelta.onAwait { it }
            result.onAwait { error -> throw AssertionError("Real extraction ended before held first Delta: ${error?.javaClass?.simpleName}") }
        }
    }

    private fun report(f: P3NativeRecoveryFixture, phase: String): MutableMap<String, Any?> = linkedMapOf(
        "kind" to "p3_real_mnn_preemption_and_process_death", "synthetic_inputs_only" to true,
        "live_model_extraction" to true, "quality_status" to "not_evaluated",
        "run_id" to f.runId, "phase" to phase, "status" to "running",
        "source_revision" to InstrumentationRegistry.getArguments().getString("p3SourceRevision"),
        "source_scope" to listOf("P3NativeRecoveryInstrumentedTest.kt", "P3NativeRecoveryFixture.kt"),
        "model_source" to "files/model-sources/secondary", "extraction_params" to EXTRACT_PARAMS,
        "chat_params" to CHAT_PARAMS, "database_name" to f.name,
        "resource_limits" to "battery>=20, thermal<SEVERE, powerSave=false; wait<=180s",
        "host_force_stop_wait_ms" to 45_000L)

    private fun foregroundHost(): android.app.Activity {
        val host = instrumentation.startActivitySync(Intent(context, CreatePersonaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        return host
    }

    private fun finishHost(host: android.app.Activity) = instrumentation.runOnMainSync {
        host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host.finish()
    }

    private data class Checkpoint(val runId: String, val databaseName: String, val modelDir: String,
        val runningId: String, val preemptedId: String, val oldPid: Int, val processToken: String,
        val consentRevision: Long)

    private companion object {
        val PROCESS_TOKEN: String = UUID.randomUUID().toString()
        val EXTRACT_PARAMS = GenerationParams(temperature = 0f, maxTokens = 256)
        val CHAT_PARAMS = GenerationParams(temperature = 0f, maxTokens = 8)
        const val PERSONA = "p3-native-persona"
        const val PREEMPT_SOURCE = "p3-native-preempt-source"
        const val DEATH_SOURCE = "p3-native-death-source"
    }
}
