package com.example.persona.core.ai.mnn

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.BuildConfig
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.prompt.NativeMessage
import com.example.persona.core.ai.prompt.NativePromptPayload
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P1SamplingInstrumentedTest {
    @Test(timeout = 1_800_000L)
    fun activeNativeSamplerAndTokenBudget() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runP1Sampling=true", arguments.getString("runP1Sampling") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        check(context.packageName.endsWith(".validation")) { "Requires isolated validation APK" }
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Requires an arm64 device" }
        val root = File(context.filesDir, "model-sources").canonicalFile
        val relative = checkNotNull(arguments.getString("samplingSource")) { "Supply samplingSource" }
        val supplied = File(relative).let { if (it.isAbsolute) it else File(root, relative) }
        val model = supplied.canonicalFile
        check(model.path.startsWith(root.path + File.separator) && model.isDirectory) {
            "Model must be below isolated private files/model-sources"
        }
        val config = File(model, "config.json").canonicalFile
        check(config.parentFile == model && config.isFile) { "Requires an existing model config" }
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "P1-sampling").apply { isDaemon = true }
        }
        val session = NativeMnnSession()
        val future = worker.submit(Callable {
            try {
                assertTrue("Native load failed", session.load(config.path))
                val raw = NativePromptPayload.RawText(
                    "<|im_start|>user\nList the numbers from 1 to 100.<|im_end|>\n<|im_start|>assistant\n"
                )
                val chat = NativePromptPayload.ChatMessages(
                    listOf(NativeMessage("user", "List the numbers from 1 to 100."))
                )

                fun run(label: String, params: GenerationParams, payload: NativePromptPayload = raw): NativeGenerationDiagnostics {
                    var deltas = 0
                    session.generate(payload, params) { deltas++; true }
                    val stats = session.generationDiagnostics()
                    assertEquals("$label token cap", params.maxTokens, stats.maxTokens)
                    assertTrue("$label must execute decode", stats.decodeSteps in 1..params.maxTokens)
                    assertTrue("$label must sample actual tokens", stats.outputTokens in 1..params.maxTokens)
                    record("phase=$label temperature=${params.temperature} topP=${params.topP} " +
                        "maxTokens=${stats.maxTokens} samplerRevision=${stats.samplerRevision} " +
                        "decodeSteps=${stats.decodeSteps} outputTokens=${stats.outputTokens} deltas=$deltas")
                    return stats
                }

                val greedy = run("greedy", GenerationParams(0f, 1f, 1))
                assertEquals(4096, session.sampleForVerification()[0])
                val capped = run("cap-only", GenerationParams(0f, 1f, 4))
                assertEquals("Changing only token cap must retain loaded sampler", greedy.samplerRevision, capped.samplerRevision)
                assertEquals("Raw token budget must permit all four decode steps", 4, capped.decodeSteps)
                assertEquals("Raw token budget must sample all four tokens", 4, capped.outputTokens)
                run("cold", GenerationParams(0.01f, 1f, 1))
                assertEquals(4096, session.sampleForVerification()[0])
                val hot = run("hot", GenerationParams(2f, 1f, 1))
                val hotCounts = session.sampleForVerification()
                assertTrue("Temperature must affect the active sampler", hotCounts[0] in 1301..2399)
                assertTrue("Hot sampler must retain low-probability bins", hotCounts[3] > 200)
                record("phase=hot-probe syntheticHistogram=${hotCounts.joinToString(",")}")

                val narrow = run("narrow", GenerationParams(2f, 0.01f, 1), chat)
                assertTrue("Sampling change must rebuild", narrow.samplerRevision > hot.samplerRevision)
                assertEquals("topP must constrain actual sampler support", 4096, session.sampleForVerification()[0])
                val narrowCap = run("chat-cap-only", GenerationParams(2f, 0.01f, 4), chat)
                assertEquals(narrow.samplerRevision, narrowCap.samplerRevision)
                assertEquals("Chat token budget must permit all four decode steps", 4, narrowCap.decodeSteps)
                assertEquals("Chat token budget must sample all four tokens", 4, narrowCap.outputTokens)
                run("wide-again", GenerationParams(2f, 1f, 1), chat)
                assertTrue("topP A-B-A retained stale sampler", session.sampleForVerification()[0] < 2400)
                run("narrow-again", GenerationParams(2f, 0.01f, 1))
                assertEquals(4096, session.sampleForVerification()[0])

                var cancelled = false
                session.generate(raw, GenerationParams(0f, 1f, 32)) {
                    cancelled = true
                    session.stop()
                    false
                }
                assertTrue("Cancellation requires a callback", cancelled)
                run("after-cancel", GenerationParams(0.01f, 1f, 1), chat)
                assertEquals(4096, session.sampleForVerification()[0])
            } finally {
                session.close()
            }
        })
        try {
            future.get(1_700_000L, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            // Do not include native/model exception messages that might contain user data.
            val cause = error.cause
            if (cause is AssertionError) throw cause
            throw AssertionError("P1 sampling failed (${cause?.javaClass?.simpleName})")
        } catch (error: TimeoutException) {
            session.stop()
            throw AssertionError("P1 sampling exceeded wall-clock deadline")
        } finally {
            future.cancel(true)
            worker.shutdownNow()
        }
    }

    private fun record(message: String) {
        Log.i("P1SamplingAcceptance", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "$message\n")
        })
    }
}
