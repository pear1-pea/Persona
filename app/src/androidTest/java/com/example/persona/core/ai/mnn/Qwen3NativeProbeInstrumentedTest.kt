package com.example.persona.core.ai.mnn

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.BuildConfig
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.prompt.NativePromptPayload
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Direct JNI research only: bypasses product routing, admission and model registry.
// The host must enforce an independent 35-minute wall-clock deadline. Any host timeout,
// JUnit timeout, or unresolved worker requires host force-stop of com.example.persona.validation
// and com.example.persona.validation.test before exporting evidence. Retain failed runs.
@RunWith(AndroidJUnit4::class)
class Qwen3NativeProbeInstrumentedTest {
    @Test(timeout = 1_800_000L)
    fun independentNativeResearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runQwen3Probe") == "true")
        var host: Activity? = null
        var worker: Qwen3ProbeWorker? = null
        var session: NativeMnnSession? = null
        var evidence: File? = null
        val rows = (Qwen3ProbeProtocol.smoke.keys + Qwen3ProbeProtocol.extraction.keys).map { id ->
            linkedMapOf<String, Any?>("id" to id, "engineering_status" to "not_run", "quality_status" to "not_evaluated")
        }
        val report = linkedMapOf<String, Any?>(
            "kind" to "qwen3-native-research-observations-v1", "scope" to "direct_native_bypasses_product_pipeline",
            "app_end_to_end_admission" to false, "adoption_count" to 0,
            "host_timeout_requires_force_stop_qa" to true, "junit_deadline_ms" to 1_800_000L,
            "host_deadline_ms" to 2_100_000L,
            "engineering_status" to "running", "quality_status" to "not_evaluated", "cases" to rows
        )
        val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
        fun checkpoint() {
            evidence?.let { destination ->
                val temporary = File(destination.parentFile, "observations.tmp")
                try {
                    FileOutputStream(temporary).use {
                        it.write(gson.toJson(report).toByteArray(Charsets.UTF_8))
                        it.fd.sync()
                    }
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
        }
        fun type(error: Throwable) = if (error is Qwen3ProbeFailure) error.errorType else error.javaClass.simpleName
        try {
            val context = instrumentation.targetContext
            require(BuildConfig.DEBUG && context.packageName == "com.example.persona.validation")
            require(instrumentation.context.packageName == "com.example.persona.validation.test")
            require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && Build.VERSION.SDK_INT >= 29)
            val runId = requireNotNull(args.getString("qwen3RunId"))
            val root = Qwen3ProbePackage.existingRoot(context.filesDir, runId)
            require(root.listFiles()?.map { it.name }?.toSet() == setOf("bundle.json", "receipt.json", "model"))
            val output = File(root, "observations.json")
            require(output.absoluteFile == output.canonicalFile && output.createNewFile())
            evidence = output
            report["run_id"] = runId
            checkpoint()
            val bundleSha = requireNotNull(args.getString("qwen3BundleSha256"))
            val revision = requireNotNull(args.getString("qwen3SourceRevision"))
            val bundle = Qwen3ProbeProtocol.parse(Qwen3ProbeJson.readFile(root, "bundle.json"), bundleSha, revision)
            report["bundle_sha256"] = bundleSha
            report["source_revision"] = revision
            report["package_receipt_sha256"] = bundle.packageReceiptSha256
            val config = Qwen3ProbePackage.verify(root, bundle.packageReceiptSha256)
            host = instrumentation.startActivitySync(Intent(context, CreatePersonaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { host!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            report["load_resource_gate"] = resources(context)
            val native = NativeMnnSession().also { session = it }
            val calls = Qwen3ProbeWorker(native).also { worker = it }
            val loadStarted = SystemClock.elapsedRealtime()
            val loaded = calls.call(90) { native.load(config.path) }
            report["load_ms"] = SystemClock.elapsedRealtime() - loadStarted
            require(loaded)
            for (case in bundle.cases) {
                val row = rows.single { it["id"] == case.id }
                if (!calls.usable) continue
                try {
                    row["resources"] = resources(context)
                    calls.call { native.reset() }
                    val ids = calls.call { native.inspectChatPromptForVerification(case.messages) }
                    row["inspection_ids_match"] = ids.toList() == case.referenceIds
                    require(ids.toList() == case.referenceIds)
                    val count = calls.call { native.countTokens(case.referenceText) }
                    row["reference_token_count"] = count
                    require(count == ids.size && count + case.maxTokens + 56 <= 4096)
                    val generated = generate(native, calls, case, case.maxTokens, row)
                    row["engineering_status"] = "passed"
                    if (case.source != null) row.putAll(Qwen3ProbeQuality.evaluate(case,
                        generated["raw_output"] as String,
                        generated["token_cap_reached"] == true || generated["output_storage_cap_reached"] == true))
                } catch (error: Throwable) {
                    row["engineering_status"] = "failed"
                    row["error_type"] = type(error)
                }
                checkpoint()
            }
            if (calls.usable) {
                report["recovery_resources"] = resources(context)
                val smoke = bundle.cases.single { it.id == "smoke-single" }
                calls.call { native.reset() }
                val inspected = calls.call { native.inspectChatPromptForVerification(smoke.messages) }
                require(inspected.toList() == smoke.referenceIds)
                require(calls.call { native.countTokens(smoke.referenceText) } == inspected.size)
                val cancelled = linkedMapOf<String, Any?>()
                report["callback_stop"] = cancelled
                generate(native, calls, smoke, 24, cancelled, cancelFirst = true)
                require(cancelled["callback_count"] == 1)
                calls.call { native.stop() }
                calls.call { native.reset() }
                require(calls.call { native.inspectChatPromptForVerification(smoke.messages) }.toList() == smoke.referenceIds)
                require(calls.call { native.countTokens(smoke.referenceText) } == smoke.referenceIds.size)
                val recovery = linkedMapOf<String, Any?>()
                report["after_reset_recovery"] = recovery
                generate(native, calls, smoke, 4, recovery)
                require((recovery["callback_count"] as Int) > 0)
                report["recovery_status"] = "passed"
            }
            require(rows.all { it["engineering_status"] == "passed" } && report["recovery_status"] == "passed")
            report["engineering_status"] = "passed"
        } catch (error: Throwable) {
            report["engineering_status"] = "failed"
            report["error_type"] = type(error)
        } finally {
            if (worker?.usable == true) {
                try { worker!!.call { session!!.close() }; report["native_cleanup"] = "closed" }
                catch (error: Throwable) {
                    report["engineering_status"] = "failed"
                    report["cleanup_error_type"] = type(error)
                }
            } else if (worker != null) {
                report["native_cleanup"] = "worker_unresolved_host_must_force_stop_validation"
                report["engineering_status"] = "failed"
            }
            try {
                host?.let { activity -> instrumentation.runOnMainSync {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.finish()
                } }
            } catch (error: Throwable) {
                report["engineering_status"] = "failed"
                report["activity_cleanup_error_type"] = type(error)
            }
            val extraction = rows.filter { it["id"] in Qwen3ProbeProtocol.extraction.keys }
            report["semantic_negative_false_extractions"] = extraction.count { it["semantic_negative_false_extraction"] == true }
            report["semantic_negative_decode_unknown"] = extraction.count {
                it["id"] in setOf("recall-question", "fiction") && it["strict_decode"] != "passed"
            }
            report["quality_status"] = Qwen3ProbeQuality.fixedSixStatus(rows)
            try { checkpoint() } catch (_: Throwable) { throw AssertionError("EvidenceWriteFailure") }
        }
        if (report["engineering_status"] != "passed") throw AssertionError("Qwen3ProbeEngineeringFailure")
    }

    private fun generate(native: NativeMnnSession, calls: Qwen3ProbeWorker, case: Qwen3ProbeCase,
        cap: Int, result: MutableMap<String, Any?>, cancelFirst: Boolean = false): Map<String, Any?> {
        val raw = StringBuffer()
        val callbacks = AtomicInteger()
        val firstMs = AtomicLong(-1)
        val cancelAtMs = AtomicLong(-1)
        val storageCap = AtomicBoolean()
        val start = SystemClock.elapsedRealtime()
        try {
            calls.call(90) {
                native.generate(NativePromptPayload.ChatMessages(case.messages), GenerationParams(0f, 0.8f, cap)) { delta ->
                    callbacks.incrementAndGet()
                    if (delta.isNotEmpty()) firstMs.compareAndSet(-1, SystemClock.elapsedRealtime() - start)
                    if (cancelFirst) cancelAtMs.compareAndSet(-1, SystemClock.elapsedRealtime())
                    if (raw.length + delta.length > 65536) { storageCap.set(true); false }
                    else { raw.append(delta); !cancelFirst }
                }
            }
        } finally {
            result.putAll(mapOf("raw_output" to raw.toString(), "callback_count" to callbacks.get(),
                "ttft_ms" to firstMs.get().takeIf { it >= 0 }, "elapsed_ms" to SystemClock.elapsedRealtime() - start,
                "output_storage_cap_reached" to storageCap.get()))
        }
        val callbackStopLatency = cancelAtMs.get().takeIf { it >= 0 }?.let { SystemClock.elapsedRealtime() - it }
        result["callback_stop_latency_ms"] = callbackStopLatency
        result["callback_stop_limit_ms"] = if (cancelFirst) Qwen3ProbeGenerationCheck.CALLBACK_STOP_LIMIT_MS else null
        val diagnostics = calls.call { native.generationDiagnostics() }
        val timings = calls.call { native.timings() }
        result.putAll(mapOf("native_timings" to timings, "diagnostics" to diagnostics,
            "token_cap_reached" to (diagnostics.outputTokens >= cap || diagnostics.decodeSteps >= cap),
            "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "native_pss_kib" to runCatching {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.nativePss.takeIf { it > 0 }
            }.getOrNull(),
            "process_status_memory_lines" to runCatching {
                File("/proc/self/status").useLines { lines ->
                    lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.toList()
                }
            }.getOrNull()))
        require(diagnostics.maxTokens == cap && diagnostics.decodeSteps in 0..cap && diagnostics.outputTokens in 0..cap)
        requireNotNull(timings)
        result["native_input_tokens_match"] = timings.inputTokens == case.referenceIds.size.toLong()
        result["nonempty_callback_and_raw"] = callbacks.get() > 0 && raw.isNotBlank()
        result["control_markers_leaked"] = Qwen3ProbeGenerationCheck.markerLeak(raw.toString())
        Qwen3ProbeGenerationCheck.verify(raw.toString(), callbacks.get(), timings.inputTokens,
            case.referenceIds.size, cancelFirst, callbackStopLatency)
        return result
    }

    private fun resources(context: Context): Map<String, Any> {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val start = SystemClock.elapsedRealtime()
        while (true) {
            require(SystemClock.elapsedRealtime() - start <= 180000)
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra("level", -1) ?: -1
            val scale = battery?.getIntExtra("scale", -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val thermal = power.currentThermalStatus
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            if (percent >= 20 && thermal in PowerManager.THERMAL_STATUS_NONE until PowerManager.THERMAL_STATUS_SEVERE && !power.isPowerSaveMode && !memory.lowMemory) {
                return mapOf("battery_percent" to percent, "thermal_status" to thermal, "power_save" to false,
                    "wait_ms" to SystemClock.elapsedRealtime() - start,
                    "available_memory_bytes" to memory.availMem, "total_memory_bytes" to memory.totalMem,
                    "system_low_memory_threshold_bytes" to memory.threshold, "low_memory" to memory.lowMemory)
            }
            require(SystemClock.elapsedRealtime() - start < 180000)
            Thread.sleep(1000)
        }
    }
}
