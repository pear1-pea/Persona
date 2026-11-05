package com.example.persona.features

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.*
import com.example.persona.core.ai.mnn.MnnLocalAiEngine
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.ai.prompt.MemoryPrompt
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomChatRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.data.repository.RoomPersonaRepository
import com.example.persona.di.DatabaseModule
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.MessageStatus
import com.example.persona.features.chat.ChatViewModel
import com.google.gson.Gson
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2MemoryRuntimeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 900_000L)
    fun actualMnnUsesPersistedRevisedAndDeletedMemoryAcrossSessions() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runP2MemoryRuntime") == "true")
        check(context.packageName == "com.example.persona.validation")
        val arguments = InstrumentationRegistry.getArguments()
        val modelSource = arguments.getString("memoryModelSource") ?: "primary"
        check(modelSource in setOf("primary", "secondary"))
        val temperatureOverride = arguments.getString("memoryProbeTemperature")?.toFloat()
        check(temperatureOverride == null || temperatureOverride == 0f)
        val source = File(context.filesDir, "model-sources/$modelSource").canonicalFile
        check(source.parentFile == File(context.filesDir, "model-sources").canonicalFile && source.isDirectory)
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(context, validator, installer)
        val nativeEngine = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val executorInputs = CopyOnWriteArrayList<Map<String, Any?>>()
        val engine = object : LocalAiEngine by nativeEngine {
            override fun streamResponse(session: GenerationSession, prompt: String,
                history: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> {
                val effectiveParams = if (temperatureOverride == null) params else params.copy(temperature = temperatureOverride)
                val system = history.firstOrNull { it.role == "system" }?.content.orEmpty()
                executorInputs += mapOf("effectiveGenerationParams" to effectiveParams,
                    "systemCharacters" to system.length,
                    "userCharacters" to prompt.length,
                    "systemContainsSavedFact" to system.contains("桂花茶"),
                    "userContainsSavedFact" to prompt.contains("桂花茶"),
                    "containsSavedFact" to (system.contains("桂花茶") || prompt.contains("桂花茶")),
                    "containsRevisedFact" to (system.contains("乌龙茶") || prompt.contains("乌龙茶")))
                return nativeEngine.streamResponse(session, prompt, history, effectiveParams)
            }
        }
        val cloudAttempts = AtomicInteger()
        val cloud = CloudChatRepository(blockedApi(CloudChatApi::class.java),
            BackendConfig("", "", false), object : AuthTokenProvider {
                override fun currentToken(): String? {
                    cloudAttempts.incrementAndGet()
                    error("Cloud is forbidden in this test")
                }
                override suspend fun refreshToken(): String? = error("Cloud is forbidden in this test")
                override fun invalidateSession() = error("Cloud is forbidden in this test")
            })
        val hybrid = HybridAiRepository(cloud, engine, manager, HybridRouter(), RoutingContextProvider(context))
        val databaseName = "p2-memory-runtime-${UUID.randomUUID()}"
        val personaId = "runtime-${UUID.randomUUID()}"
        val records = mutableListOf<Map<String, Any?>>()
        val runId = InstrumentationRegistry.getArguments().getString("p2MemoryRunId") ?: "current"
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val output = File(context.filesDir, "p2-evaluation/memory-runtime-$runId.json")
        val report = linkedMapOf<String, Any?>(
            "kind" to "actual_mnn_explicit_memory_smoke", "syntheticOnly" to true,
            "personaId" to personaId, "repetitionsPerPhase" to 1,
            "generationParams" to GenerationParams(), "subjectiveQuality" to "not_run",
            "diagnosticTemperatureOverride" to temperatureOverride, "modelSource" to modelSource,
            "effectiveGenerationParams" to GenerationParams().copy(temperature = temperatureOverride ?: GenerationParams().temperature),
            "memoryPromptVersion" to MemoryPrompt.VERSION,
            "records" to records, "executorInputs" to executorInputs, "runId" to runId
        )
        var installed: InstalledModel? = null
        var data: Data? = null
        var activeStore: ViewModelStore? = null
        try {
            check(manager.refreshInstalledModels().isEmpty()) { "Requires an empty isolated model catalog" }
            check(manager.deviceCapability().thermalStatus !in setOf(ThermalStatus.SEVERE,
                ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) { "Cool device before test" }
            val result = installer.installFromDirectory(source, manager.modelsRoot(), manager.deviceCapability())
            check(result is ModelPackageInstallResult.Success) { "Fixture model installation failed" }
            val selected = result.model
            installed = selected
            assertEquals(selected.id, hybrid.selectLocalModel(selected.modelDir)?.id)
            assertTrue(hybrid.initializeLocalModel())
            report["modelId"] = selected.id
            report["modelVersion"] = selected.version
            report["manifestSha256"] = MessageDigest.getInstance("SHA-256")
                .digest(File(source, "manifest.json").readBytes()).joinToString("") { "%02x".format(it) }

            data = open(databaseName)
            checkNotNull(data).database.personaDao().insertPersona(PersonaEntity(personaId, "岚", "", "",
                "你是虚构的山间书屋管理员岚，语气温和，用简洁自然的中文回答。用户事实只能依据明确提供的信息，不知道时直接说明。",
                "OFFLINE_USER", false))

            suspend fun reopen() {
                data?.database?.close()
                data = open(databaseName)
            }

            suspend fun round(phase: String) {
                val coolingStarted = SystemClock.elapsedRealtime()
                withTimeout(180_000L) {
                    while (manager.deviceCapability().thermalStatus in setOf(ThermalStatus.SEVERE,
                        ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
                        delay(2_000L)
                    }
                }
                val coolingWaitMs = SystemClock.elapsedRealtime() - coolingStarted
                val current = checkNotNull(data)
                val evidence = current.memories.selectForPrompt(personaId, false)
                assertTrue(current.memories.selectForPrompt(personaId, true).isEmpty())
                check(manager.deviceCapability().thermalStatus !in setOf(ThermalStatus.CRITICAL,
                    ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) { "Thermal safety stop" }
                val store = ViewModelStore()
                activeStore = store
                lateinit var model: ChatViewModel
                instrumentation.runOnMainSync {
                    model = ChatViewModel(hybrid, current.personas, current.chat, current.auth, current.memories)
                    store.put("runtime", model)
                    model.loadPersonaInfo(personaId)
                }
                withTimeout(30_000L) { while (model.currentPersona.value?.id != personaId) delay(50) }
                val started = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync {
                    model.sendMessage("根据你当前可用的信息，我最喜欢喝什么茶？如果不知道就直接说不知道。一句话回答。", localOnly = true)
                }
                withTimeout(180_000L) { while (model.isGenerating.value) delay(100) }
                val reply = current.chat.getRecentMessages(personaId, 2).last()
                val decision = checkNotNull(hybrid.lastRoutingDecision.value)
                records += mapOf("phase" to phase, "durationMs" to SystemClock.elapsedRealtime() - started,
                    "coolingWaitMs" to coolingWaitMs,
                    "backend" to hybrid.activeMode.value.name, "status" to reply.status.name,
                    "evidence" to evidence.map { mapOf("id" to it.id, "revision" to it.revision) },
                    "response" to reply.content, "excludedFromHistory" to reply.excludeFromHistory,
                    "cloudAllowed" to decision.cloudAllowed, "thermal" to manager.deviceCapability().thermalStatus.name)
                assertFalse(reply.isFromUser)
                assertEquals(MessageStatus.NORMAL, reply.status)
                assertTrue(reply.content.isNotBlank())
                assertEquals(HybridAiRepository.Mode.LOCAL, hybrid.activeMode.value)
                assertFalse(decision.cloudAllowed)
                assertTrue(reply.localOnly)
                assertEquals(evidence.isNotEmpty(), reply.excludeFromHistory)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "P2_MEMORY_RUNTIME phase=$phase status=${reply.status} evidence=${evidence.size}\n")
                })
                instrumentation.runOnMainSync { store.clear() }
                activeStore = null
                delay(2_500L)
            }

            round("unknown-baseline")
            val note = checkNotNull(data).memories.save(personaId, MemoryType.PREFERENCE, "用户明确保存：最喜欢喝桂花茶。", false)
            reopen()
            assertEquals(note.id, checkNotNull(data).memories.selectForPrompt(personaId, false).single().id)
            round("persisted-memory")
            val revised = checkNotNull(data).memories.save(personaId, MemoryType.PREFERENCE,
                "用户明确更正：现在最喜欢喝乌龙茶，之前的偏好已经作废。", false, id = note.id, expectedRevision = 1)
            assertEquals(2, revised.revision)
            reopen()
            round("revised-memory")
            checkNotNull(data).memories.delete(note.id, 2)
            reopen()
            assertTrue(checkNotNull(data).memories.selectForPrompt(personaId, false).isEmpty())
            assertTrue(checkNotNull(checkNotNull(data).database.memoryDao().findOwned(note.id, "OFFLINE_USER")).deleted)
            round("deleted-memory")
            assertEquals(0, cloudAttempts.get())
            report["transportStatus"] = "passed"
            val savedResponse = records.single { it["phase"] == "persisted-memory" }["response"] as String
            val revisedResponse = records.single { it["phase"] == "revised-memory" }["response"] as String
            val deletedResponse = records.single { it["phase"] == "deleted-memory" }["response"] as String
            val recall = mapOf("savedFactUsed" to savedResponse.contains("桂花茶"),
                "revisedFactUsed" to (revisedResponse.contains("乌龙茶") && !revisedResponse.contains("桂花茶")),
                "deletedFactAbsent" to (!deletedResponse.contains("桂花茶") && !deletedResponse.contains("乌龙茶")))
            report["targetedRecall"] = recall
            report["targetedRecallStatus"] = if (recall.values.all { it }) "passed" else "failed"
            assertTrue("Persisted memory was not used", recall.getValue("savedFactUsed"))
            assertTrue("Revised memory was not used", recall.getValue("revisedFactUsed"))
            assertTrue("Deleted memory was repeated", recall.getValue("deletedFactAbsent"))
        } catch (error: Throwable) {
            if (report["transportStatus"] != "passed") report["transportStatus"] = "failed"
            report["failureClass"] = error.javaClass.simpleName
            throw error
        } finally {
            activeStore?.let { store -> instrumentation.runOnMainSync { store.clear() } }
            data?.database?.close()
            hybrid.clearLocalModel()
            installed?.let { check(hybrid.deleteLocalModel(it.modelDir)) }
            context.deleteDatabase(databaseName)
            report["cloudAttempts"] = cloudAttempts.get()
            output.parentFile?.mkdirs()
            output.writeText(Gson().toJson(report))
        }
    }

    private data class Data(
        val database: AppDatabase,
        val auth: AuthManager,
        val chat: RoomChatRepository,
        val personas: RoomPersonaRepository,
        val memories: RoomMemoryRepository
    )

    private fun open(name: String): Data {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val auth = AuthManager(blockedApi(AuthApi::class.java), SessionTokenStore(context), database.personaDao())
        check(auth.identity.value.ownerId == "OFFLINE_USER") { "Requires isolated offline identity" }
        return Data(database, auth, RoomChatRepository(database.messageDao(), auth),
            RoomPersonaRepository(database.personaDao(), auth), RoomMemoryRepository(database.memoryDao(), auth))
    }

    private fun <T : Any> blockedApi(type: Class<T>): T = checkNotNull(type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { _, _, _ -> error("Network API is forbidden in this test") }))
}
