package com.example.persona.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.ai.*
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.repository.*
import com.example.persona.di.DatabaseModule
import com.google.gson.Gson
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Real Room/Auth/Hybrid with synthetic model files, engine and SSE. No JNI or network.
@RunWith(AndroidJUnit4::class)
class P4ExtractionCascadeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "p4-cascade-${UUID.randomUUID()}"
    private val directory = File(context.cacheDir, name).canonicalFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferenceNames = mutableSetOf<String>()
    private lateinit var db: AppDatabase
    private lateinit var ai: HybridAiRepository
    private lateinit var repository: RoomMemoryCurationRepository
    private var network = NetworkState.UNMETERED
    private var localOutput = "invalid JSON"
    private var cloudOutput = VALID
    private var localCalls = 0
    private var cloudCalls = 0
    private var afterLocal: suspend () -> Unit = {}

    @Before fun prepare() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runP4Cascade") == "true")
        check(context.packageName == "com.example.persona.validation")
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val isolated = "$name-$key"
                preferenceNames += isolated
                return super.getSharedPreferences(isolated, mode)
            }
            override fun getExternalFilesDir(type: String?): File = File(directory, "managed").apply { mkdirs() }
        }
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val authApi = Proxy.newProxyInstance(AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)) { _, method, _ ->
            check(method.name == "login")
            AuthSessionDto("p4-synthetic-token", "2099-01-01T00:00:00Z", AuthUserDto(OWNER, "p4@example.invalid"))
        } as AuthApi
        val auth = AuthManager(authApi, SessionTokenStore(scoped), db.personaDao())
        auth.signIn("p4@example.invalid", "synthetic-password")
        val cloud = CloudChatRepository(object : CloudChatApi {
            override fun streamChat(request: ChatRequest, authorization: String?): Call<ResponseBody> {
                check(authorization == "Bearer p4-synthetic-token")
                check(request.messages.last().content.contains(CONTENT))
                check(!request.messages.last().content.contains("invalid JSON"))
                cloudCalls++
                return SyntheticCall(cloudOutput)
            }
        }, BackendConfig("https://p4-cascade.invalid/", "synthetic-model", true), auth)
        val engine = object : LocalAiEngine {
            override val state = MutableStateFlow<EngineState>(EngineState.Idle)
            override suspend fun initialize(model: InstalledModel): Boolean { state.value = EngineState.Ready; return true }
            override suspend fun smokeTest(model: InstalledModel) = true
            override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
                params: GenerationParams): Flow<GenerationEvent> = flow {
                localCalls++
                emit(GenerationEvent.Delta(localOutput))
                emit(GenerationEvent.Completed(GenerationMetrics(0, 1, 1, 1.0)))
                afterLocal()
            }
            override fun stopGeneration(session: GenerationSession) = Unit
            override fun release() { state.value = EngineState.Idle }
        }
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val installer = ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker)
        val manager = LocalModelManager(scoped, validator, installer)
        val profile = manager.deviceCapability()
        check(profile.batteryPercent >= 20 && !profile.powerSaveMode && profile.thermalStatus < ThermalStatus.SEVERE)
        val installed = installer.installFromDirectory(modelFixture(), manager.modelsRoot(), profile)
        check(installed is ModelPackageInstallResult.Success) {
            (installed as? ModelPackageInstallResult.Failure)?.reason ?: "Unexpected install result"
        }
        ai = HybridAiRepository(cloud, engine, manager, HybridRouter(), { network }, scope)
        check(ai.selectLocalModel(installed.model.modelDir) != null)
        check(ai.initializeLocalModel())
        repository = RoomMemoryCurationRepository(db, auth, RoomMemoryRepository(db.memoryDao(), auth), ai)
        db.personaDao().insertPersona(PersonaEntity(PERSONA, "Synthetic", "", "", "", OWNER))
        db.messageDao().insertMessage(MessageEntity(SOURCE, PERSONA, OWNER, "请记住：$CONTENT", true, 1))
        repository.configure(true, true)
    }

    @After fun cleanup() = runBlocking<Unit> {
        scope.cancel()
        withTimeout(10_000) { scope.coroutineContext.job.join() }
        if (::ai.isInitialized) ai.clearLocalModel()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
        preferenceNames.forEach { context.deleteSharedPreferences(it) }
        directory.deleteRecursively()
    }

    @Test fun optedInContractFailureCreatesOnlyOnePendingCandidate() = runBlocking<Unit> {
        repository.proposeWithModel(PERSONA, SOURCE, false, true)
        assertEquals(1, localCalls)
        assertEquals(1, cloudCalls)
        val candidate = repository.observe(PERSONA).first().single()
        assertEquals(CONTENT, candidate.content)
        assertTrue(candidate.extractorVersion.endsWith("upgrade=true"))
        assertTrue(db.memoryDao().selectForPrompt(OWNER, PERSONA, false, System.currentTimeMillis()).isEmpty())
    }

    @Test fun noOptInNeverAutomaticallyUploads() = runBlocking<Unit> {
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false) }
        assertEquals(0, cloudCalls)
        assertEquals(1, localCalls)
    }

    @Test fun absentCloudConsentAndLocalOnlyEachBlockUpgrade() = runBlocking<Unit> {
        repository.configure(true, false)
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        repository.configure(true, true)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET localOnly=1 WHERE id=?", arrayOf(SOURCE))
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        assertEquals(0, cloudCalls)
    }

    @Test fun meteredUnknownAndOfflineNeverAutomaticallyUpload() = runBlocking<Unit> {
        for (state in listOf(NetworkState.METERED, NetworkState.UNKNOWN, NetworkState.OFFLINE)) {
            network = state
            assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        }
        assertEquals(0, cloudCalls)
    }

    @Test fun revocationBeforeUpgradeStopsSecondDispatch() = runBlocking<Unit> {
        afterLocal = { repository.configure(true, false) }
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        assertEquals(0, cloudCalls)
    }

    @Test fun changedSourceBeforeUpgradeStopsSecondDispatch() = runBlocking<Unit> {
        afterLocal = { db.messageDao().updateMessageContent(SOURCE, OWNER, "changed source", "NORMAL") }
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        assertEquals(0, cloudCalls)
    }

    @Test fun secondContractFailureCannotProduceThirdAttempt() = runBlocking<Unit> {
        cloudOutput = "invalid JSON"
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, false, true) }
        assertEquals(1, cloudCalls)
        assertTrue(repository.observe(PERSONA).first().isEmpty())
    }

    @Test fun validEmptyLocalResultDoesNotEscalate() = runBlocking<Unit> {
        localOutput = """{"suggestions":[]}"""
        repository.proposeWithModel(PERSONA, SOURCE, false, true)
        assertEquals(0, cloudCalls)
        assertTrue(repository.observe(PERSONA).first().isEmpty())
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        val failure = runCatching { withTimeout(10_000) { block() } }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("A timeout is not rejection acceptance", failure is TimeoutCancellationException)
    }

    private fun modelFixture(): File {
        val source = File(directory, "source").apply { mkdirs() }
        val files = mapOf("config.json" to """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight"}""",
            "llm.mnn" to "synthetic graph", "llm.mnn.weight" to "synthetic weights", "tokenizer.txt" to "synthetic tokenizer\n")
        files.forEach { (name, value) -> File(source, name).writeText(value) }
        val entries = files.keys.map { name ->
            val bytes = File(source, name).readBytes()
            mapOf("path" to name, "role" to "file", "size" to bytes.size,
                "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        File(source, "manifest.json").writeText(Gson().toJson(mapOf("schemaVersion" to 1,
            "id" to "p4-synthetic", "name" to "Synthetic only", "version" to "1", "backend" to "MNN",
            "family" to "QWEN2_5", "promptFormat" to "QWEN_CHATML_TEXT", "entry" to "llm.mnn",
            "tokenizer" to "tokenizer.txt", "contextWindow" to 4096, "minRamGb" to 0,
            "minSdk" to 24, "requiredAbis" to listOf("arm64-v8a"), "files" to entries)))
        File(source, "READY").writeText("ready")
        return source
    }

    private class SyntheticCall(private val content: String) : Call<ResponseBody> {
        private val cancelled = AtomicBoolean()
        private val executed = AtomicBoolean()
        override fun enqueue(callback: Callback<ResponseBody>) { callback.onResponse(this, execute()) }
        override fun execute(): Response<ResponseBody> {
            check(executed.compareAndSet(false, true) && !cancelled.get())
            val chunk = Gson().toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to content)))))
            return Response.success("data: $chunk\n\ndata: [DONE]\n\n".toResponseBody())
        }
        override fun clone(): Call<ResponseBody> = SyntheticCall(content)
        override fun isExecuted() = executed.get()
        override fun isCanceled() = cancelled.get()
        override fun cancel() { cancelled.set(true) }
        override fun request() = Request.Builder().url("https://p4-cascade.invalid/").build()
        override fun timeout() = Timeout.NONE
    }

    companion object {
        private const val OWNER = "p4-synthetic-owner"
        private const val PERSONA = "p4-synthetic-persona"
        private const val SOURCE = "p4-synthetic-source"
        private const val CONTENT = "我喜欢简短解释。"
        private val VALID = """{"suggestions":[{"type":"PREFERENCE","content":"我喜欢简短解释。"}]}"""
    }
}
