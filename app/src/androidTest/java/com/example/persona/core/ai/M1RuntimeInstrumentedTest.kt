package com.example.persona.core.ai

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.BuildConfig
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call

@RunWith(AndroidJUnit4::class)
class M1RuntimeInstrumentedTest {
    @Test(timeout = 1_800_000L)
    fun isolatedNativeLifecycle() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with runM1Runtime=true", arguments.getString("runM1Runtime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        check(context.packageName.endsWith(".validation")) { "Requires isolated validation APK" }
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Requires an arm64 device" }
        val externalRoot = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val sourceRoot = File(context.filesDir, "model-sources").canonicalFile
        check(sourceRoot.parentFile == context.filesDir.canonicalFile) { "Unsafe model-sources root" }
        val primarySource = sourceDirectory(arguments.getString("primarySource"), sourceRoot)
        val secondarySource = sourceDirectory(arguments.getString("secondarySource"), sourceRoot)
        check(primarySource != secondarySource &&
            !primarySource.path.startsWith(secondarySource.path + File.separator) &&
            !secondarySource.path.startsWith(primarySource.path + File.separator)) {
            "Supply two separate model package directories"
        }

        val checker = AndroidSymlinkChecker
        val validator = LocalModelValidator(checker)
        val installer = ModelPackageInstaller(context, validator, checker)
        val manager = LocalModelManager(context, validator, installer)
        val modelsRoot = manager.modelsRoot().canonicalFile
        check(modelsRoot.parentFile == externalRoot && modelsRoot.name == "models") {
            "Unsafe installed-model root"
        }
        val cloudCalls = AtomicInteger()
        val cloud = CloudChatRepository(
            object : CloudChatApi {
                override fun streamChat(request: ChatRequest, authorization: String?): Call<ResponseBody> {
                    cloudCalls.incrementAndGet()
                    error("Cloud API must never be called")
                }
            },
            BackendConfig(baseUrl = "", modelId = "", isConfigured = false),
            object : AuthTokenProvider {
                override fun currentToken(): String? = error("Auth must never be called")
                override suspend fun refreshToken(): String? = error("Auth must never be called")
                override fun invalidateSession() = error("Auth must never be called")
            }
        )
        val engine = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val repository = HybridAiRepository(cloud, engine, manager, HybridRouter(), RoutingContextProvider(context))
        val worker = deadlineWorker("M1-lifecycle")
        val installed = CopyOnWriteArrayList<InstalledModel>()
        var lifecycleFailure: Throwable? = null
        try {
            bounded(worker, "reset-isolated-models", 120_000L) {
                repository.clearLocalModel()
                repository.refreshLocalModels().forEach { report ->
                    assertEquals(modelsRoot, File(report.modelDir).canonicalFile.parentFile)
                    assertTrue("Remove only isolated test models", repository.deleteLocalModel(report.modelDir))
                }
            }
            bounded(worker, "device", 30_000L) {
                val profile = manager.deviceCapability()
                record("sdk=${profile.sdk} abi=${profile.abi} ramBytes=${profile.totalRamBytes} " +
                    "availableBytes=${profile.availableMemBytes} thermal=${profile.thermalStatus} " +
                    "batteryPercent=${profile.batteryPercent} powerSave=${profile.powerSaveMode}")
            }
            fun install(source: File, label: String): InstalledModel = bounded(worker, label, 600_000L) {
                val result = installer.installFromDirectory(source, modelsRoot, manager.deviceCapability())
                val failureReason = (result as? ModelPackageInstallResult.Failure)?.reason
                assertTrue("$label must pass real package validation: $failureReason", result is ModelPackageInstallResult.Success)
                (result as ModelPackageInstallResult.Success).model.also { model ->
                    installed.add(model)
                    assertEquals(modelsRoot, File(model.modelDir).canonicalFile.parentFile)
                    assertTrue(File(model.modelDir, LocalModelValidator.READY_MARKER).isFile)
                    record("phase=$label modelId=${model.id} bytes=${model.totalSizeBytes} " +
                        "family=${model.family} promptFormat=${model.promptFormat}")
                }
            }
            fun activate(model: InstalledModel, label: String) = bounded(worker, label, 240_000L) {
                assertEquals(model.id, repository.selectLocalModel(model.modelDir)?.id)
                assertTrue("$label load and smoke must pass", repository.initializeLocalModel())
                assertEquals(EngineState.Ready, engine.state.value)
                val decision = repository.selectRouteForGeneration(
                    forceCloud = false, localOnly = true,
                    privacyLevel = PrivacyLevel.PRIVATE, taskComplexity = TaskComplexity.SIMPLE
                )
                assertEquals(Route.LOCAL, decision.route)
                assertEquals(HybridAiRepository.Mode.LOCAL, repository.activeMode.value)
            }

            val primary = install(primarySource, "install-primary")
            val secondary = install(secondarySource, "install-secondary")
            assertFalse("Second package needs a distinct model ID", primary.id == secondary.id)
            assertFalse("Second package needs different model payloads", payloadHashes(primary) == payloadHashes(secondary))
            activate(primary, "load-primary")
            bounded(worker, "short-primary", 180_000L) { generate(repository, "short-primary") }
            bounded(worker, "cancel-first-delta", 180_000L) {
                generate(repository, "cancel", StopReason.USER_REQUESTED) { repository.stopGeneration(it) }
                assertEquals(EngineState.Ready, engine.state.value)
                assertEquals(0, manager.recentFailureCount(primary.id))
            }
            bounded(worker, "generate-after-cancel", 180_000L) { generate(repository, "after-cancel") }
            activate(secondary, "switch-secondary")
            bounded(worker, "short-secondary", 180_000L) { generate(repository, "short-secondary") }
            bounded(worker, "record-transient", 30_000L) {
                manager.recordLocalLoadFailure(secondary, type = LocalModelFailureType.TRANSIENT)
                assertTrue(manager.recentFailureCount(secondary.id) > 0)
            }
            activate(secondary, "reselect-current-retry")
            assertNull(manager.recentFailure(secondary.id))
            bounded(worker, "generate-after-retry", 180_000L) { generate(repository, "after-retry") }
            bounded(worker, "delete-idle", 45_000L) {
                assertTrue(repository.deleteLocalModel(secondary.modelDir))
                assertDeleted(secondary, manager, repository, engine)
            }
            activate(primary, "reload-primary")
            bounded(worker, "delete-generating", 180_000L) {
                generate(repository, "delete", StopReason.ENGINE_RELEASED) {
                    assertTrue("Deletion must start from ready runtime", engine.state.value == EngineState.Ready)
                    assertTrue(repository.deleteLocalModel(primary.modelDir))
                }
                assertDeleted(primary, manager, repository, engine)
                assertTrue(manager.refreshInstalledModels().isEmpty())
            }
            assertEquals("No cloud requests allowed", 0, cloudCalls.get())
            assertTrue("Primary source copy must remain", primarySource.isDirectory)
            assertTrue("Secondary source copy must remain", secondarySource.isDirectory)
        } catch (error: Throwable) {
            lifecycleFailure = error
            throw error
        } finally {
            // A separate worker can attempt release even if JNI blocked the lifecycle worker.
            val cleanup = deadlineWorker("M1-cleanup")
            try {
                bounded(cleanup, "release-and-cleanup", 45_000L) {
                    engine.release()
                    repository.clearLocalModel()
                    installed.asReversed().forEach { model ->
                        if (File(model.modelDir).exists()) {
                            assertTrue("Cleanup must remove installed copy", repository.deleteLocalModel(model.modelDir))
                        }
                    }
                    assertEquals(EngineState.Idle, engine.state.value)
                }
            } catch (cleanupError: Throwable) {
                val failure = lifecycleFailure
                if (failure == null) throw cleanupError
                failure.addSuppressed(cleanupError)
            } finally {
                worker.shutdownNow()
                cleanup.shutdownNow()
            }
        }
    }

    private fun sourceDirectory(argument: String?, root: File): File {
        check(!argument.isNullOrBlank()) { "Both primarySource and secondarySource are required" }
        val supplied = File(argument)
        val candidate = if (supplied.isAbsolute) supplied else File(root, argument)
        val source = candidate.canonicalFile
        check(source.path.startsWith(root.path + File.separator) && source.isDirectory) {
            "Model sources must be directories below isolated private files/model-sources"
        }
        check(!AndroidSymlinkChecker.isSymbolicLink(candidate)) { "Source cannot be a symlink" }
        return source
    }

    private fun payloadHashes(model: InstalledModel): List<String> {
        val gson = Gson()
        val manifest = File(model.manifestPath).reader().use { gson.fromJson(it, LocalModelManifest::class.java) }
        val config = File(model.modelDir, "config.json").reader().use { gson.fromJson(it, JsonObject::class.java) }
        return listOf(model.entry, config.get("llm_weight").asString).map { path ->
            checkNotNull(manifest.files?.firstOrNull { it.path == path }?.sha256)
        }
    }

    private suspend fun generate(
        repository: HybridAiRepository,
        label: String,
        expectedStop: StopReason? = null,
        afterFirstDelta: suspend (GenerationSession) -> Unit = {}
    ) {
        val session = GenerationSession()
        val startedAt = SystemClock.elapsedRealtime()
        var firstDeltaAt: Long? = null
        var stopRequestedAt: Long? = null
        var deltaCount = 0
        var terminalCount = 0
        try {
            repository.streamResponse(
                mode = HybridAiRepository.Mode.LOCAL,
                session = session,
                systemPrompt = "Answer briefly and follow the requested format.",
                userMessage = if (expectedStop == null) "Reply with the word hello." else
                    "List the numbers from 1 to 200, one number per line.",
                route = Route.LOCAL
            ).collect { event ->
                assertEquals("$label emitted after terminal event", 0, terminalCount)
                when (event) {
                    is GenerationEvent.Delta -> {
                        assertTrue("$label delta must contain text", event.text.isNotEmpty())
                        deltaCount++
                        if (firstDeltaAt == null) {
                            firstDeltaAt = SystemClock.elapsedRealtime()
                            if (expectedStop != null) stopRequestedAt = SystemClock.elapsedRealtime()
                            afterFirstDelta(session)
                        }
                    }
                    is GenerationEvent.Completed -> {
                        terminalCount++
                        assertNull("$label must stop rather than complete", expectedStop)
                        assertTrue("$label must report output tokens", event.metrics.outputTokens > 0)
                        record("phase=$label outputTokens=${event.metrics.outputTokens} " +
                            "generationMs=${event.metrics.totalLatencyMs} " +
                            "engineTtftMs=${event.metrics.firstTokenLatencyMs} " +
                            "tokensPerSecond=${event.metrics.tokensPerSecond}")
                    }
                    is GenerationEvent.Stopped -> {
                        terminalCount++
                        assertEquals("$label stop reason", expectedStop, event.reason)
                        record("phase=$label stopReason=${event.reason} " +
                            "stopLatencyMs=${stopRequestedAt?.let { SystemClock.elapsedRealtime() - it }}")
                    }
                    is GenerationEvent.Failed -> throw AssertionError(
                        "$label failed: backend=${event.backend} type=${event.error.type}"
                    )
                }
            }
            assertTrue("$label must stream at least one delta", deltaCount > 0)
            assertEquals("$label needs exactly one terminal event", 1, terminalCount)
            record("phase=$label deltas=$deltaCount ttftMs=${firstDeltaAt?.minus(startedAt)} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        } finally {
            repository.stopGeneration(session)
        }
    }

    private fun assertDeleted(
        model: InstalledModel,
        manager: LocalModelManager,
        repository: HybridAiRepository,
        engine: MnnLocalAiEngine
    ) {
        assertFalse("Installed copy must be gone", File(model.modelDir).exists())
        assertNull(manager.currentModel.value)
        assertEquals(EngineState.Idle, engine.state.value)
        assertEquals(HybridAiRepository.Mode.CLOUD, repository.activeMode.value)
    }

    private fun deadlineWorker(name: String): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun <T> bounded(
        worker: ExecutorService,
        label: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        record("phase=$label started deadlineMs=$timeoutMs")
        val future = worker.submit(Callable { runBlocking { block() } })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw AssertionError("$label exceeded ${timeoutMs}ms wall-clock deadline")
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is AssertionError) throw cause
            throw AssertionError("$label failed (${cause?.javaClass?.simpleName})")
        } finally {
            if (!future.isDone) future.cancel(true)
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            record("phase=$label wallMs=${SystemClock.elapsedRealtime() - startedAt} " +
                "totalPssKb=${memory.totalPss} nativePssKb=${memory.nativePss}")
        }
    }

    private fun record(message: String) {
        Log.i(TAG, message)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "$message\n")
        })
    }

    private companion object {
        const val TAG = "M1RuntimeAcceptance"
    }
}
