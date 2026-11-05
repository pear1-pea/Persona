package com.example.persona.features

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import com.example.persona.core.ai.*
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryCandidateEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.repository.*
import com.example.persona.di.DatabaseModule
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class P3NativeRecoveryFixture(val context: Context, val runId: String) {
    val name = "p3-native-$runId"
    val directory = File(context.filesDir, "p3-native-recovery/$runId").canonicalFile
    val checkpoint = File(directory, "checkpoint.json")
    val observations = File(directory, "observations.json")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scoped = object : ContextWrapper(context) {
        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
            check(key in setOf("persona_session", "local_model_settings"))
            return super.getSharedPreferences("$name-$key", mode)
        }
        override fun getExternalFilesDir(type: String?): File {
            check(type == "models")
            return File(directory, "models").apply { check(isDirectory || mkdirs()) }
        }
    }
    val validator = LocalModelValidator(AndroidSymlinkChecker)
    val installer = ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker)
    val manager = LocalModelManager(scoped, validator, installer)
    val networkCalls = AtomicInteger()
    var database: AppDatabase? = null
    private val gson = Gson()

    fun open(hybrid: HybridAiRepository): RoomMemoryCurationRepository {
        check(database == null)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        database = db
        val auth = AuthManager(blocked(AuthApi::class.java), SessionTokenStore(scoped), db.personaDao())
        check(auth.identity.value.ownerId == OWNER && !auth.isLoggedIn.value)
        return RoomMemoryCurationRepository(db, auth, RoomMemoryRepository(db.memoryDao(), auth), hybrid)
    }

    fun hybrid(engine: LocalAiEngine): HybridAiRepository = HybridAiRepository(
        CloudChatRepository(blocked(CloudChatApi::class.java), BackendConfig("", "", false),
            object : com.example.persona.core.auth.AuthTokenProvider {
                override fun currentToken(): String? = forbiddenNetwork()
                override suspend fun refreshToken(): String? = forbiddenNetwork()
                override fun invalidateSession() { forbiddenNetwork() }
            }), engine, manager, HybridRouter(), RoutingContextProvider(scoped)::networkState, scope)

    private fun forbiddenNetwork(): Nothing {
        networkCalls.incrementAndGet()
        error("Network is forbidden")
    }

    private fun <T> blocked(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)) { _, _, _ -> forbiddenNetwork() })

    fun sync(file: File, value: Any) {
        check(directory.isDirectory || directory.mkdirs())
        check(file.canonicalFile.parentFile == directory)
        FileOutputStream(file).use { output ->
            output.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun tasks(): List<MemoryCandidateEntity> {
        val db = checkNotNull(database)
        return db.openHelper.readableDatabase.query("SELECT * FROM memory_candidates").use { c ->
            val ids = mutableListOf<MemoryCandidateEntity>()
            while (c.moveToNext()) {
                fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
                ids += MemoryCandidateEntity(text("id"), text("ownerId"), text("personaId"),
                    text("sourceMessageId"), text("sourceHash"), text("content"), text("type"),
                    text("topicKey"), text("extractorVersion"), c.getInt(c.getColumnIndexOrThrow("manual")) != 0,
                    text("status"), c.getString(c.getColumnIndexOrThrow("memoryId")),
                    c.getLong(c.getColumnIndexOrThrow("createdAt")))
            }
            ids
        }
    }

    fun assertNoMemories() {
        checkNotNull(database).openHelper.readableDatabase.query("SELECT COUNT(*) FROM memories").use {
            check(it.moveToFirst() && it.getLong(0) == 0L) { "Extraction must not save formal memories" }
        }
        check(tasks().none { it.status == "PENDING" || it.status == "ACCEPTED" })
    }

    suspend fun awaitResources() {
        withTimeout(180_000L) {
            while (true) {
                val p = manager.deviceCapability()
                if (p.batteryPercent >= 20 && !p.powerSaveMode && p.thermalStatus < ThermalStatus.SEVERE) break
                delay(2_000L)
            }
        }
    }

    fun assertOldProcessDead(pid: Int) {
        check(pid > 0 && pid != android.os.Process.myPid())
        try {
            Os.kill(pid, 0)
            error("Prepared process still exists; host must force-stop it")
        } catch (error: ErrnoException) {
            check(error.errno == OsConstants.ESRCH) { "Cannot establish prepared process death" }
        }
    }

    fun checkedModel(path: String): File {
        val root = manager.modelsRoot().canonicalFile
        check(root == File(directory, "models").absoluteFile)
        check(root.path.startsWith(context.filesDir.canonicalPath + File.separator))
        val model = File(path)
        check(!AndroidSymlinkChecker.isSymbolicLink(model))
        check(model.absoluteFile == model.canonicalFile && model.canonicalFile.parentFile == root)
        return model
    }

    fun deleteDeadProcessModel(path: String, pid: Int) {
        assertOldProcessDead(pid)
        val model = checkedModel(path)
        model.walkTopDown().forEach { check(!AndroidSymlinkChecker.isSymbolicLink(it)) }
        check(!model.exists() || model.deleteRecursively())
    }

    fun cleanupStorage() {
        database?.close()
        database = null
        if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
        for (key in listOf("persona_session", "local_model_settings")) {
            val prefs = "$name-$key"
            check(context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit())
            check(context.deleteSharedPreferences(prefs))
        }
        check(!checkpoint.exists() || checkpoint.delete())
    }

    companion object {
        const val OWNER = "OFFLINE_USER"
    }
}

internal class P3NativeRecoveryEngine(private val delegate: LocalAiEngine) : LocalAiEngine by delegate {
    class Gate {
        val firstDelta = CompletableDeferred<GenerationSession>()
        val cancelled = CompletableDeferred<Unit>()
    }
    data class Call(val sessionId: String, val params: GenerationParams, val startedMs: Long,
        var deltas: Int = 0, var terminal: String? = null, var failureClass: String? = null,
        var errorType: String? = null)
    val calls = CopyOnWriteArrayList<Call>()
    val lifecycle = CopyOnWriteArrayList<String>()
    @Volatile var gate: Gate? = null

    override suspend fun initialize(model: InstalledModel): Boolean {
        lifecycle += "initialize"
        return delegate.initialize(model)
    }
    override suspend fun smokeTest(model: InstalledModel): Boolean {
        lifecycle += "smokeTest"
        return delegate.smokeTest(model)
    }
    override fun stopGeneration(session: GenerationSession) {
        lifecycle += "stop:${session.id}:${session.stopReason}"
        delegate.stopGeneration(session)
    }
    override fun release() {
        lifecycle += "release"
        delegate.release()
    }
    override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
        params: GenerationParams): Flow<GenerationEvent> = flow {
        val hold = gate
        val call = Call(session.id, params, SystemClock.elapsedRealtime())
        calls += call
        try {
            delegate.streamResponse(session, prompt, history, params).collect { event ->
                if (event is GenerationEvent.Delta) call.deltas++
                if (event !is GenerationEvent.Delta) call.terminal = event.javaClass.simpleName
                if (event is GenerationEvent.Failed) call.errorType = event.error.type.name
                emit(event)
                if (event is GenerationEvent.Delta && hold != null && !hold.firstDelta.isCompleted) {
                    hold.firstDelta.complete(session)
                    try { awaitCancellation() } finally { hold.cancelled.complete(Unit) }
                }
            }
        } catch (error: Throwable) {
            call.failureClass = error.javaClass.simpleName
            throw error
        }
    }
}

internal class P3NativeRecoveryNoopEngine : LocalAiEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    val executionCalls = AtomicInteger()
    private fun forbidden(): Nothing {
        executionCalls.incrementAndGet()
        error("No model execution is permitted during recovery")
    }
    override suspend fun initialize(model: InstalledModel): Boolean = forbidden()
    override suspend fun smokeTest(model: InstalledModel): Boolean = forbidden()
    override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
        params: GenerationParams): Flow<GenerationEvent> = forbidden()
    override fun stopGeneration(session: GenerationSession) { forbidden() }
    override fun release() = Unit
}
