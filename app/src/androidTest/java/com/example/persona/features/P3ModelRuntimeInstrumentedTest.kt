package com.example.persona.features

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
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
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.memory.MemoryExtraction
import com.example.persona.core.memory.MemoryRanker
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomMemoryCurationRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.di.DatabaseModule
import com.example.persona.features.creation.CreatePersonaActivity
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P3ModelRuntimeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 2_400_000L)
    fun actualModelsExtractConfirmedCandidatesAndMeasureRetrieval() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runP3Model") == "true")
        check(context.packageName == "com.example.persona.validation")
        val runId = requireNotNull(args.getString("p3RunId"))
        check(runId.matches(Regex("[a-z0-9-]{1,40}")))
        val directory = File(context.filesDir, "p3-runtime/$runId").apply { mkdirs() }
        val output = File(directory, "observations.json")
        check(!output.exists())
        val input = File(directory, "samples.jsonl")
        val chosen = setOf("explicit-tea", "explicit-negation", "explicit-goal", "recall-question", "fiction", "conflicting-preference")
        val samples = input.readLines().filter { it.isNotBlank() }.map { JsonParser().parse(it).asJsonObject }
            .filter { it.get("id").asString in chosen }
        check(samples.size == chosen.size)
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(context, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(context, validator, installer)
        val native = MnnLocalAiEngine(PromptAdapterRegistry(), NativeSessionFactory { NativeMnnSession() })
        val raw = StringBuilder()
        var calls = 0
        val engine = object : LocalAiEngine by native {
            override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
                params: GenerationParams): Flow<GenerationEvent> {
                calls++
                check(params == GenerationParams(temperature = 0f, maxTokens = 256))
                return kotlinx.coroutines.flow.flow {
                    native.streamResponse(session, prompt, history, params).collect {
                        if (it is GenerationEvent.Delta) raw.append(it.text)
                        emit(it)
                    }
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cloud = CloudChatRepository(blocked(CloudChatApi::class.java), BackendConfig("", "", false),
            object : com.example.persona.core.auth.AuthTokenProvider {
                override fun currentToken(): String? = error("Cloud is forbidden")
                override suspend fun refreshToken(): String? = error("Cloud is forbidden")
                override fun invalidateSession() = error("Cloud is forbidden")
            })
        val hybrid = HybridAiRepository(cloud, engine, manager, HybridRouter(),
            RoutingContextProvider(context)::networkState, scope)
        val records = mutableListOf<Map<String, Any?>>()
        val metrics = mutableListOf<Map<String, Any?>>()
        val report = linkedMapOf<String, Any?>("run_id" to runId, "synthetic_only" to true,
            "source_revision" to args.getString("p3SourceRevision"),
            "extraction_version" to MemoryExtraction.VERSION, "retrieval_version" to MemoryRanker.VERSION,
            "parameters" to GenerationParams(temperature = 0f, maxTokens = 256),
            "records" to records, "retrieval_metrics" to metrics, "status" to "running")
        fun checkpoint() { output.writeText(Gson().toJson(report), Charsets.UTF_8) }
        val host = instrumentation.startActivitySync(Intent(context, CreatePersonaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            check(manager.refreshInstalledModels().isEmpty())
            for (arm in listOf("primary", "secondary")) {
                report["active_arm"] = arm
                report["stage"] = "model_admission"
                checkpoint()
                awaitResources(manager)
                val installed = installer.installFromDirectory(File(context.filesDir, "model-sources/$arm"),
                    manager.modelsRoot(), manager.deviceCapability())
                check(installed is ModelPackageInstallResult.Success)
                check(hybrid.selectLocalModel(installed.model.modelDir) != null)
                check(hybrid.initializeLocalModel())
                for (sample in samples) {
                    report["active_case"] = sample.get("id").asString
                    report["stage"] = "resource_wait"
                    checkpoint()
                    val wait = awaitResources(manager)
                    val before = manager.deviceCapability()
                    val name = "p3-runtime-${UUID.randomUUID()}"
                    val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                        .addCallback(DatabaseModule.memoryIntegrityCallback).build()
                    val scoped = object : ContextWrapper(context) {
                        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                            super.getSharedPreferences("$name-$key", mode)
                    }
                    try {
                        val owner = "OFFLINE_USER"
                        db.personaDao().insertPersona(PersonaEntity("p", "fixture", "", "", "", owner))
                        val source = sample.get("source").asString
                        db.messageDao().insertMessage(MessageEntity("s", "p", owner, source, true, 1,
                            localOnly = true))
                        val auth = AuthManager(blocked(AuthApi::class.java), SessionTokenStore(scoped), db.personaDao())
                        val memories = RoomMemoryRepository(db.memoryDao(), auth)
                        val repository = RoomMemoryCurationRepository(db, auth, memories, hybrid)
                        repository.configure(true, false)
                        val rules = MemoryExtraction.explicit(source)
                        raw.setLength(0)
                        val callStart = calls
                        val start = SystemClock.elapsedRealtime()
                        report["stage"] = "extracting"
                        checkpoint()
                        val failure = runCatching { repository.proposeWithModel("p", "s", false) }.exceptionOrNull()
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        val candidates = repository.observe("p").first()
                        assertTrue(db.memoryDao().selectForPrompt(owner, "p", false, System.currentTimeMillis()).isEmpty())
                        assertTrue(candidates.all { source.contains(it.content) })
                        var confirmed = false
                        if (candidates.isNotEmpty()) {
                            val candidate = candidates.first()
                            val saved = repository.confirm(candidate.id, candidate.content, candidate.type)
                            assertFalse(saved.cloudAllowed)
                            assertEquals(saved.id, repository.confirm(candidate.id, candidate.content, candidate.type).id)
                            memories.delete(saved.id, saved.revision)
                            assertTrue(memories.selectForPrompt("p", false).isEmpty())
                            confirmed = true
                        }
                        val expected = sample.getAsJsonArray("model").map {
                            it.asJsonObject.get("content").asString
                        }.sorted()
                        val actual = candidates.map { it.content }.sorted()
                        records += mapOf("arm" to arm, "model" to installed.model.id,
                            "case_id" to sample.get("id").asString, "duration_ms" to (SystemClock.elapsedRealtime() - start),
                            "resource_wait_ms" to wait, "battery" to before.batteryPercent,
                            "thermal" to before.thermalStatus.name, "engine_calls" to (calls - callStart),
                            "rule_candidates" to rules.size, "model_candidates" to actual,
                            "expected" to expected, "exact_content_match" to (failure == null && actual == expected),
                            "status" to if (failure == null) "completed" else "rejected",
                            "error_class" to failure?.javaClass?.simpleName, "raw_synthetic_output" to raw.toString(),
                            "manual_confirmation_and_deletion" to confirmed)
                        checkpoint()
                    } finally {
                        db.close()
                        context.deleteDatabase(name)
                        context.deleteSharedPreferences("$name-persona_session")
                    }
                }
                val name = "p3-retrieval-${UUID.randomUUID()}"
                val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                try {
                    val auth = AuthManager(blocked(AuthApi::class.java), SessionTokenStore(context), db.personaDao())
                    val owner = auth.identity.value.ownerId
                    for (i in 1..600) db.memoryDao().insert(MemoryEntity("n-$i", owner, "p", "FACT",
                        "今天晴朗，记录 $i", createdAt = 1, updatedAt = i.toLong()))
                    db.memoryDao().insert(MemoryEntity("old", owner, "p", "FACT", "我不喝咖啡", createdAt = 1, updatedAt = 0))
                    val repository = RoomMemoryRepository(db.memoryDao(), auth)
                    val times = (1..20).map {
                        val start = System.nanoTime()
                        assertEquals(listOf("old"), repository.selectForPrompt("p", false, "coffee").map { it.id })
                        (System.nanoTime() - start) / 1_000_000.0
                    }.sorted()
                    metrics += mapOf("arm" to arm, "rows" to 601, "p50_ms" to times[10],
                        "p95_ms" to times[18], "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
                        "process_status" to File("/proc/self/status").readLines().filter {
                            it.startsWith("VmRSS:") || it.startsWith("VmHWM:") })
                } finally { db.close(); context.deleteDatabase(name) }
                hybrid.clearLocalModel()
                check(hybrid.deleteLocalModel(installed.model.modelDir))
            }
            report["status"] = "completed"
            report["engine_calls"] = calls
            checkpoint()
        } finally {
            withContext(NonCancellable) {
                hybrid.clearLocalModel()
                scope.cancel()
                native.release()
                instrumentation.runOnMainSync {
                    host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    host.finish()
                }
                checkpoint()
            }
        }
    }

    private suspend fun awaitResources(manager: LocalModelManager): Long {
        val start = SystemClock.elapsedRealtime()
        withTimeout(600_000L) {
            while (true) {
                val p = manager.deviceCapability()
                if (p.batteryPercent >= 20 && !p.powerSaveMode && p.thermalStatus < ThermalStatus.SEVERE) return@withTimeout
                delay(2000)
            }
        }
        return SystemClock.elapsedRealtime() - start
    }

    private fun <T> blocked(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader,
        arrayOf(type)) { _, _, _ -> error("Real network is forbidden") })
}
