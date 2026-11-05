package com.example.persona.evaluation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.*
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.MemoryPrompt
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.features.creation.CreatePersonaActivity
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val MIN_EVALUATION_BATTERY_PERCENT = 20

@RunWith(AndroidJUnit4::class)
class M5LlmBatchInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 10_800_000L)
    fun exportActualModelResponsesForHumanReview() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runM5LlmBatch") == "true")
        check(context.packageName == "com.example.persona.validation")
        val runId = checkNotNull(args.getString("m5RunId"))
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val directory = File(context.filesDir, "m5-llm/$runId")
        val output = File(directory, "responses.json")
        check(!output.exists()) { "Earlier model responses cannot be overwritten" }
        val input = File(directory, "bundle.json")
        check(input.length() in 1..2_000_000L)
        val bytes = input.readBytes()
        val bundle = M5LlmFixtures.read(bytes, checkNotNull(args.getString("m5BundleSha256")),
            checkNotNull(args.getString("m5SourceRevision")))
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(context, validator, installer)
        val native = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val effective = CopyOnWriteArrayList<GenerationParams>()
        val engine = object : LocalAiEngine by native {
            override fun streamResponse(session: GenerationSession, prompt: String,
                history: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> {
                check(params == GenerationParams()) { "Frozen production parameters changed" }
                effective += params
                return native.streamResponse(session, prompt, history, params)
            }
        }
        val cloudAttempts = AtomicInteger()
        val cloudApi = checkNotNull(CloudChatApi::class.java.cast(Proxy.newProxyInstance(
            CloudChatApi::class.java.classLoader, arrayOf(CloudChatApi::class.java)
        ) { _, _, _ -> cloudAttempts.incrementAndGet(); error("Cloud transport forbidden") }))
        val cloud = CloudChatRepository(cloudApi, BackendConfig("", "", false), object : AuthTokenProvider {
            override fun currentToken(): String? { cloudAttempts.incrementAndGet(); error("Cloud forbidden") }
            override suspend fun refreshToken(): String? = error("Cloud forbidden")
            override fun invalidateSession() = error("Cloud forbidden")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val network = RoutingContextProvider(context)
        val hybrid = HybridAiRepository(cloud, engine, manager, HybridRouter(), network::networkState, scope)
        val records = mutableListOf<Map<String, Any?>>()
        val configs = linkedMapOf<String, Any?>()
        val report = linkedMapOf<String, Any?>(
            "kind" to "actual_mnn_subjective_batch", "adapter_version" to M5LlmFixtures.VERSION,
            "dataset_version" to M5Fixtures.DATASET_VERSION, "dataset_sha256" to M5Fixtures.DATASET_SHA256,
            "synthetic_data_only" to true, "run_id" to runId, "split" to bundle.split,
            "process_id" to android.os.Process.myPid(),
            "execution_surface" to "foreground_create_persona_host",
            "evaluation_resource_policy" to mapOf("min_battery_percent" to MIN_EVALUATION_BATTERY_PERCENT,
                "power_save_mode" to false, "thermal_below" to "SEVERE"),
            "source_revision" to bundle.revision, "input_bundle_sha256" to M5Fixtures.sha256(bytes),
            "target_apk_sha256" to hashFile(File(context.applicationInfo.sourceDir)),
            "test_apk_sha256" to hashFile(File(instrumentation.context.applicationInfo.sourceDir)),
            "config_snapshots" to configs, "records" to records,
            "effective_generation_params" to effective, "execution_status" to "running",
            "quality_decision" to "pending_human_review", "seed_control" to "unsupported",
            "started_at_epoch_ms" to System.currentTimeMillis())
        val installed = mutableListOf<InstalledModel>()
        var foregroundHost: Activity? = null
        fun checkpoint() {
            val temporary = File(directory, "responses.tmp")
            temporary.writeText(M5Fixtures.observationJson(report), Charsets.UTF_8)
            check(temporary.renameTo(output)) { "Private response checkpoint failed" }
        }
        try {
            val host = instrumentation.startActivitySync(Intent(context, CreatePersonaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            foregroundHost = host
            instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            instrumentation.waitForIdleSync()
            check(manager.refreshInstalledModels().isEmpty()) { "An empty isolated catalog is required" }
            for ((arm, sourceName) in listOf("baseline" to "primary", "candidate" to "secondary")) {
                report["active_arm"] = arm
                report["stage"] = "before_model_admission"
                checkpoint()
                awaitEvaluationResources(manager)
                val source = File(context.filesDir, "model-sources/$sourceName")
                val result = installer.installFromDirectory(source, manager.modelsRoot(), manager.deviceCapability())
                check(result is ModelPackageInstallResult.Success) { "Model package did not validate" }
                installed += result.model
                check(hybrid.selectLocalModel(result.model.modelDir) != null)
                check(hybrid.initializeLocalModel()) { "Model initialization failed" }
                configs[arm] = mapOf("model" to mapOf("id" to result.model.id, "version" to result.model.version,
                    "manifest_sha256" to hashFile(File(source, "manifest.json")),
                    "config_sha256" to hashFile(File(source, "config.json")),
                    "runtime" to "MNN-3.6.1-d407447ed56c4121a11ccbd266dc184ca1ead0c2", "quantization" to "int4"),
                    "prompt" to "production-ChatViewModel-persona-json-v2",
                    "parameters" to GenerationParams(), "repetitions_per_case" to 3,
                    "retrieval" to "latest-authorized-v1-top4-1200utf16",
                    "context_assembly" to MemoryPrompt.VERSION,
                    "other_sampler_fields" to "model config plus pinned runtime defaults; not overridden")
                checkpoint()
                for (case in bundle.cases) for (repetition in 1..3) {
                    report["active_case"] = case.id
                    report["active_arm"] = arm
                    report["active_repetition"] = repetition
                    report["stage"] = "before_admission"
                    checkpoint()
                    val row = linkedMapOf<String, Any?>("case_id" to case.id, "arm" to arm, "repetition" to repetition)
                    if (case.cloud) {
                        row.putAll(mapOf("status" to "unsupported", "response" to null,
                            "authorized_evidence_refs" to emptyList<String>(), "duration_ms" to 0,
                            "reason" to "No real cloud executor configured; local substitution is forbidden"))
                    } else {
                        try {
                            val waiting = awaitEvaluationResources(manager)
                            val before = manager.deviceCapability()
                            row["battery_before"] = before.batteryPercent
                            row["power_save_before"] = before.powerSaveMode
                            row["thermal_before"] = before.thermalStatus.name
                            report["stage"] = "generating"
                            checkpoint()
                            row.putAll(M5ActualChatRound(instrumentation).run(case, hybrid))
                            row["resource_wait_ms"] = waiting
                        } catch (error: Throwable) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            row.putAll(mapOf("status" to "failed", "response" to null,
                                "authorized_evidence_refs" to emptyList<String>(), "duration_ms" to 0,
                                "failure_class" to error.javaClass.simpleName,
                                "failure_location" to error.stackTrace.take(5).map { it.toString() }))
                        }
                    }
                    val after = manager.deviceCapability()
                    row["thermal_after"] = after.thermalStatus.name
                    row["battery_after"] = after.batteryPercent
                    records += row
                    report["stage"] = "recorded"
                    checkpoint()
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("stream", "M5_LLM arm=$arm case=${case.id} repetition=$repetition status=${row["status"]}\n")
                    })
                    delay(2_000L)
                }
                hybrid.clearLocalModel()
            }
            check(cloudAttempts.get() == 0)
            report["execution_status"] = "completed"
        } catch (error: Throwable) {
            report["execution_status"] = "failed"
            report["failure_class"] = error.javaClass.simpleName
            throw IllegalStateException("Real model batch failed; inspect private evidence")
        } finally {
            try {
                hybrid.clearLocalModel()
                for (model in installed) check(hybrid.deleteLocalModel(model.modelDir))
            } finally {
                scope.cancel()
                report["cloud_attempts"] = cloudAttempts.get()
                report["finished_at_epoch_ms"] = System.currentTimeMillis()
                try {
                    checkpoint()
                } finally {
                    foregroundHost?.let { host -> instrumentation.runOnMainSync {
                        host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        host.finish()
                    } }
                }
            }
        }
    }

    private suspend fun awaitEvaluationResources(manager: LocalModelManager): Long {
        val start = SystemClock.elapsedRealtime()
        withTimeout(600_000L) {
            var profile = manager.deviceCapability()
            while (profile.batteryPercent < MIN_EVALUATION_BATTERY_PERCENT || profile.powerSaveMode ||
                profile.thermalStatus in setOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL,
                    ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
                delay(2_000L)
                profile = manager.deviceCapability()
            }
        }
        return SystemClock.elapsedRealtime() - start
    }

    private fun hashFile(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
