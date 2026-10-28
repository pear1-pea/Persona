package com.example.persona.core.ai

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P1PerformanceInstrumentedTest {
    @Test(timeout = 900_000L)
    fun sustainedFiveMinutesWithMemorySampling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runP1Performance") == "true")
        val tokenLimit = arguments.getString("performanceMaxTokens")?.toIntOrNull() ?: 64
        val interRoundDelayMs = arguments.getString("performanceDelayMs")?.toLongOrNull() ?: 0L
        require(tokenLimit in 1..256 && interRoundDelayMs in 0..10_000L)
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.persona.validation")
        val source = File(context.filesDir, "model-sources/primary")
        check(source.isDirectory)
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(context, validator, installer)
        val engine = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val sampler = Executors.newSingleThreadScheduledExecutor()
        val peakRssKb = AtomicLong()
        val peakPssKb = AtomicLong()
        fun sample() {
            peakRssKb.accumulateAndGet(procMemory("VmRSS"), ::maxOf)
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            peakPssKb.accumulateAndGet(memory.totalPss.toLong(), ::maxOf)
        }
        fun record(text: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$text\n") })
        var installed: InstalledModel? = null
        try {
            manager.clearCurrentModel()
            manager.refreshInstalledModels().forEach { check(manager.deleteModel(it.modelDir)) }
            val result = installer.installFromDirectory(source, manager.modelsRoot(), manager.deviceCapability())
            check(result is ModelPackageInstallResult.Success) { "Performance fixture install failed" }
            val model = result.model
            installed = model
            val profile = manager.deviceCapability()
            check(profile.thermalStatus !in setOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL,
                ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) { "Cool device before benchmark" }
            record("P1_PERF model=${model.id} sdk=${profile.sdk} ramBytes=${profile.totalRamBytes} " +
                "availableBytes=${profile.availableMemBytes} thermalStart=${profile.thermalStatus} " +
                "maxTokens=$tokenLimit interRoundDelayMs=$interRoundDelayMs")
            sampler.scheduleAtFixedRate({ sample() }, 0, 500, TimeUnit.MILLISECONDS)
            val loadStart = SystemClock.elapsedRealtime()
            assertTrue(engine.initialize(model))
            assertTrue(engine.smokeTest(model))
            record("P1_PERF loadAndSmokeMs=${SystemClock.elapsedRealtime() - loadStart}")
            val rates = mutableListOf<Double>()
            val ttfts = mutableListOf<Long>()
            var rounds = 0
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < 300_000L) {
                val thermal = manager.deviceCapability().thermalStatus
                if (thermal in setOf(ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
                    sample()
                    record("P1_PERF THERMAL_ABORT elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                        "thermal=$thermal sampledPeakRssKb=${peakRssKb.get()} sampledPeakPssKb=${peakPssKb.get()}")
                    error("Thermal safety stop: $thermal")
                }
                val session = GenerationSession()
                var completed = false
                var deltas = 0
                withTimeout(90_000L) {
                    engine.streamResponse(session, "List integers from 1 to 100, one per line.",
                        params = GenerationParams(temperature = 0f, topP = 1f, maxTokens = tokenLimit)).collect { event ->
                        when (event) {
                            is GenerationEvent.Delta -> deltas++
                            is GenerationEvent.Completed -> {
                                completed = true
                                rates += event.metrics.tokensPerSecond
                                event.metrics.firstTokenLatencyMs?.let(ttfts::add)
                                record("P1_PERF nativeInputTokens=${event.metrics.nativeInputTokens} " +
                                    "nativeOutputTokens=${event.metrics.nativeOutputTokens} " +
                                    "prefillMicros=${event.metrics.prefillMicros} decodeMicros=${event.metrics.decodeMicros} " +
                                    "samplingMicros=${event.metrics.samplingMicros}")
                                assertTrue(event.metrics.nativeOutputTokens?.let { it > 0 } == true)
                                assertTrue(event.metrics.prefillMicros?.let { it >= 0 } == true)
                            }
                            is GenerationEvent.Failed -> error("Performance generation failed: ${event.error.type}")
                            is GenerationEvent.Stopped -> error("Unexpected performance stop: ${event.reason}")
                        }
                    }
                }
                assertTrue(completed && deltas > 0)
                rounds++
                record("P1_PERF round=$rounds elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "generationRate=${rates.last()} thermal=${manager.deviceCapability().thermalStatus}")
                delay(interRoundDelayMs)
            }
            sample()
            assertTrue("RSS/PSS sampling must be available", peakRssKb.get() > 0 && peakPssKb.get() > 0)
            record("P1_PERF COMPLETED rounds=$rounds sustainedMs=${SystemClock.elapsedRealtime() - started} " +
                "meanFirstFiveRate=${rates.take(5).average()} meanLastFiveRate=${rates.takeLast(5).average()} " +
                "meanTtftMs=${ttfts.average()} sampledPeakRssKb=${peakRssKb.get()} " +
                "sampledPeakPssKb=${peakPssKb.get()} processVmHwmKb=${procMemory("VmHWM")} " +
                "thermalEnd=${manager.deviceCapability().thermalStatus}")
            assertTrue(rounds > 1)
        } finally {
            sampler.shutdownNow()
            sampler.awaitTermination(5, TimeUnit.SECONDS)
            engine.release()
            manager.clearCurrentModel()
            installed?.let { assertTrue(manager.deleteModel(it.modelDir)) }
        }
    }

    private fun procMemory(field: String): Long {
        val line = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("$field:") } }
        return line?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
    }
}
