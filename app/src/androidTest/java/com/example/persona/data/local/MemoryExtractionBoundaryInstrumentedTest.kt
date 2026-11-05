package com.example.persona.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.withTransaction
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
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomMemoryCurationRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.di.DatabaseModule
import com.example.persona.domain.model.MemoryType
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Validation only: real repositories/gates, synthetic in-memory Retrofit Calls, no sockets or JNI.
@RunWith(AndroidJUnit4::class)
class MemoryExtractionBoundaryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "p3-boundaries-${UUID.randomUUID()}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = CopyOnWriteArrayList<BoundaryCall>()
    private val dispatched = Channel<BoundaryCall>(Channel.UNLIMITED)
    private val routeReads = AtomicInteger()
    private val preferences = mutableSetOf<String>()
    private lateinit var db: AppDatabase
    private lateinit var auth: AuthManager
    private lateinit var memories: RoomMemoryRepository
    private lateinit var repository: RoomMemoryCurationRepository
    private lateinit var ai: HybridAiRepository
    @Volatile private var loginOwner = OWNER
    @Volatile private var network = NetworkState.UNMETERED
    @Volatile private var inspectDispatch: (ChatRequest) -> Unit = {}

    @Before fun setUp() = runBlocking<Unit> {
        assumeTrue("Requires -e runP3Boundaries true", InstrumentationRegistry.getArguments()
            .getString("runP3Boundaries") == "true")
        check(context.packageName == "com.example.persona.validation") {
            "P3 boundaries must run in the isolated validation package"
        }
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val isolated = "$name-$key"
                preferences.add(isolated)
                return super.getSharedPreferences(isolated, mode)
            }
            override fun getExternalFilesDir(type: String?): File =
                File(checkNotNull(super.getExternalFilesDir(null)), "$name/${type.orEmpty()}")
                    .also { it.mkdirs() }
        }
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val authApi = Proxy.newProxyInstance(AuthApi::class.java.classLoader,
            arrayOf(AuthApi::class.java)) { _, method, _ ->
            when (method.name) {
                "login" -> AuthSessionDto("synthetic-$loginOwner", "2099-01-01T00:00:00Z",
                    AuthUserDto(loginOwner, "$loginOwner@example.invalid"))
                "logout" -> Response.success<Void>(204, null)
                else -> error("Unexpected auth transport: ${method.name}")
            }
        } as AuthApi
        auth = AuthManager(authApi, SessionTokenStore(scoped), db.personaDao())
        auth.signIn("$OWNER@example.invalid", "synthetic-password")
        val cloudApi = object : CloudChatApi {
            override fun streamChat(request: ChatRequest, authorization: String?): Call<ResponseBody> {
                check(authorization == "Bearer synthetic-${auth.currentRepoId}")
                inspectDispatch(request)
                return BoundaryCall(request).also {
                    calls.add(it)
                    check(dispatched.trySend(it).isSuccess)
                }
            }
        }
        val cloud = CloudChatRepository(cloudApi,
            BackendConfig("https://p3-boundaries.invalid/", "synthetic-model", true), auth)
        val engine = object : LocalAiEngine {
            override val state = MutableStateFlow<EngineState>(EngineState.Idle)
            override suspend fun initialize(model: InstalledModel): Boolean = error("JNI is forbidden")
            override suspend fun smokeTest(model: InstalledModel): Boolean = error("JNI is forbidden")
            override fun streamResponse(session: GenerationSession, prompt: String,
                history: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> =
                error("Local execution is forbidden")
            override fun stopGeneration(session: GenerationSession) = Unit
            override fun release() = Unit
        }
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val manager = LocalModelManager(scoped, validator,
            ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker))
        ai = HybridAiRepository(cloud, engine, manager, HybridRouter(), {
            routeReads.incrementAndGet()
            network
        }, scope)
        memories = RoomMemoryRepository(db.memoryDao(), auth)
        repository = RoomMemoryCurationRepository(db, auth, memories, ai)
        seed(OWNER, PERSONA, SOURCE)
        repository.configure(true, true)
    }

    @After fun cleanup() = runBlocking<Unit> {
        scope.cancel()
        calls.forEach { it.cancel() }
        withTimeout(WAIT) { scope.coroutineContext.job.join() }
        dispatched.close()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
        preferences.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        context.getExternalFilesDir(null)?.let { File(it, name).deleteRecursively() }
    }

    @Test fun cloudExtractionNeedsSeparateConsentAndDoesNotGrantMemoryUpload() = runBlocking<Unit> {
        repository.configure(true, false)
        // A normal chat succeeds with the same login while extraction consent is absent.
        val chat = startChat()
        nextCall().finish()
        assertTrue(withTimeout(WAIT) { chat.await() }.any { it is GenerationEvent.Completed })
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, cloud = true) }
        assertEquals(1, calls.size)
        repository.configure(true, true)
        successfulExtraction()
        val candidate = repository.observe(PERSONA).first().single()
        val saved = repository.confirm(candidate.id, CONTENT, MemoryType.PREFERENCE)
        assertFalse(saved.cloudAllowed)
        assertTrue(memories.selectForPrompt(PERSONA, true).isEmpty())
        assertEquals(saved.id, memories.selectForPrompt(PERSONA, false).single().id)
    }

    @Test fun localOnlySourceIsRejectedBeforeRouteOrTransportAdmission() = runBlocking<Unit> {
        seed(OWNER, "private-persona", "private-source", localOnly = true)
        val reads = routeReads.get()
        assertRejected { repository.proposeWithModel("private-persona", "private-source", cloud = true) }
        assertEquals(reads, routeReads.get())
        assertTrue(calls.isEmpty())
        assertNoRunningOrPending()
    }

    @Test fun offlineRouteDeniesCloudEvenWithExtractionConsent() = runBlocking<Unit> {
        network = NetworkState.OFFLINE
        assertRejected { repository.proposeWithModel(PERSONA, SOURCE, cloud = true) }
        assertTrue(routeReads.get() > 0)
        assertFalse(requireNotNull(ai.lastRoutingDecision.value).isExecutable)
        assertTrue(calls.isEmpty())
        assertNoRunningOrPending()
    }

    @Test fun cloudConsentWithdrawalCancelsInFlightSseAndDropsLateOutput() = runBlocking<Unit> {
        invalidateInFlight { repository.configure(true, false) }
        assertFalse(repository.preferences().cloudExtractionAllowed)
    }

    @Test fun disablingExtractionCancelsInFlightSse() = runBlocking<Unit> {
        invalidateInFlight { repository.configure(false, false) }
        assertFalse(repository.preferences().enabled)
    }

    @Test fun sourceEditCancelsInFlightSseAndDropsLateOutput() = runBlocking<Unit> {
        invalidateInFlight { db.messageDao().updateMessageContent(SOURCE, OWNER, "已修改原文", "NORMAL") }
    }

    @Test fun sourceDeletionCancelsInFlightSseAndDropsLateOutput() = runBlocking<Unit> {
        invalidateInFlight { db.messageDao().deleteMessagesByPersonaId(PERSONA, OWNER) }
        assertNull(db.messageDao().getMessageById(SOURCE, OWNER))
    }

    @Test fun ignoringCandidateCannotBeUndoneByInFlightModelOutput() = runBlocking<Unit> {
        repository.propose(PERSONA, SOURCE)
        val candidate = repository.observe(PERSONA).first().single()
        val task = startExtraction()
        val call = nextCall()
        repository.ignore(candidate.id)
        assertFailed(task)
        assertCanceled(call)
        call.deliverLate()
        assertEquals("IGNORED", db.memoryCurationDao().candidate(OWNER, candidate.id)?.status)
        assertTrue(repository.observe(PERSONA).first().isEmpty())
        assertNoRunningOrPending()
    }

    @Test fun cloudPermissionOffThenOnCannotReuseOldConsentRevision() = runBlocking<Unit> {
        val task = startExtraction()
        val call = nextCall()
        val revision = requireNotNull(db.memoryCurationDao().preferences(OWNER)).revision
        // Keep extraction enabled so task invalidation cannot hide a broken consent revision check.
        // Observers see only the final cloudAllowed=true state after this transaction commits.
        db.withTransaction {
            repository.configure(true, false)
            repository.configure(true, true)
        }
        assertEquals(revision + 2, db.memoryCurationDao().preferences(OWNER)?.revision)
        assertTrue(repository.preferences().cloudExtractionAllowed)
        assertFailed(task)
        assertCanceled(call)
        call.deliverLate()
        assertNoRunningOrPending()
        successfulExtraction()
    }

    @Test fun accountSwitchCancelsOldOwnersTaskWithoutWritingIntoNewOwner() = runBlocking<Unit> {
        val previous = auth.identity.value
        invalidateInFlight {
            loginOwner = "p3-other-owner"
            auth.signIn("$loginOwner@example.invalid", "synthetic-password")
        }
        assertNotSame(previous, auth.identity.value)
        assertEquals(loginOwner, auth.currentRepoId)
        assertNull(db.memoryCurationDao().preferences(loginOwner))
        assertTrue(db.memoryCurationDao().observe(loginOwner, PERSONA).first().isEmpty())
        // New identity can obtain its own consent and use the gate after old-task cleanup.
        seed(loginOwner, "other-persona", "other-source")
        repository.configure(true, true)
        successfulExtraction("other-persona", "other-source")
        assertTrue(db.memoryCurationDao().observe(OWNER, PERSONA).first().isEmpty())
    }

    @Test fun logoutCancelsInFlightTaskWithoutCreatingOfflineCandidates() = runBlocking<Unit> {
        invalidateInFlight { auth.logout() }
        assertEquals("OFFLINE_USER", auth.currentRepoId)
        assertNull(auth.currentToken())
        assertTrue(db.memoryCurationDao().observe("OFFLINE_USER", PERSONA).first().isEmpty())
        assertNull(db.memoryCurationDao().preferences("OFFLINE_USER"))
    }

    @Test fun sameOwnerAndTokenStillRequireTheOriginalIdentityInstance() = runBlocking<Unit> {
        val previous = auth.identity.value
        invalidateInFlight { auth.signIn("$OWNER@example.invalid", "synthetic-password") }
        assertEquals(previous.ownerId, auth.identity.value.ownerId)
        assertEquals(previous.token, auth.identity.value.token)
        assertNotSame(previous, auth.identity.value)
        successfulExtraction()
    }

    @Test fun activeChatDeniesExtractionBeforeRoutingAndKeepsChatAlive() = runBlocking<Unit> {
        val chat = startChat()
        val chatCall = nextCall()
        val reads = routeReads.get()
        assertFailed(startExtraction())
        assertEquals("Background admission must precede routing", reads, routeReads.get())
        assertEquals(1, calls.size)
        assertFalse(chatCall.isCanceled())
        assertNoRunningOrPending()
        chatCall.finish()
        assertTrue(withTimeout(WAIT) { chat.await() }.any { it is GenerationEvent.Completed })
        successfulExtraction()
    }

    @Test fun admittedChatCancelsExtractionJobAndWaitsForSseCleanup() = runBlocking<Unit> {
        val task = startExtraction()
        val extractionCall = nextCall()
        val cleanedBeforeChatDispatch = AtomicBoolean()
        inspectDispatch = { request ->
            if (request.messages.last().content == "SYNTHETIC_CHAT") {
                cleanedBeforeChatDispatch.set(extractionCall.isCanceled() && extractionCall.readerClosed.isCompleted)
            }
        }
        val chat = startChat()
        val chatCall = nextCall()
        assertTrue("Chat dispatched before extraction transport cleanup", cleanedBeforeChatDispatch.get())
        assertCanceled(extractionCall)
        assertFailed(task)
        assertEquals("SYNTHETIC_CHAT", chatCall.requestBody.messages.last().content)
        extractionCall.deliverLate()
        assertNoRunningOrPending()
        chatCall.finish()
        assertTrue(withTimeout(WAIT) { chat.await() }.any { it is GenerationEvent.Completed })
        successfulExtraction()
    }

    @Test fun concurrentExtractionIsDeniedAndCallerCancellationReleasesGate() = runBlocking<Unit> {
        val first = startExtraction()
        val call = nextCall()
        val reads = routeReads.get()
        assertFailed(startExtraction())
        assertEquals(reads, routeReads.get())
        assertEquals(1, calls.size)
        assertFalse(call.isCanceled())
        withTimeout(WAIT) { first.cancelAndJoin() }
        assertTrue(first.isCancelled)
        assertCanceled(call)
        call.deliverLate()
        assertNoRunningOrPending()
        successfulExtraction()
    }

    private suspend fun seed(owner: String, persona: String, source: String, localOnly: Boolean = false) {
        db.personaDao().insertPersona(PersonaEntity(persona, "synthetic", "", "", "", owner))
        db.messageDao().insertMessage(MessageEntity(source, persona, owner, "请记住：$CONTENT", true,
            1L, localOnly = localOnly))
    }

    private fun startExtraction(persona: String = PERSONA, source: String = SOURCE) = scope.async {
        runCatching { repository.proposeWithModel(persona, source, cloud = true) }
    }

    private fun startChat() = scope.async {
        val decision = ai.selectRouteForGeneration(forceCloud = true,
            privacyLevel = PrivacyLevel.PUBLIC, taskComplexity = TaskComplexity.SIMPLE)
        check(decision.isExecutable && decision.cloudAllowed)
        val session = GenerationSession().also { it.bindRoutingDecision(decision) }
        ai.streamResponse(HybridAiRepository.Mode.CLOUD, session, "Synthetic system", "SYNTHETIC_CHAT",
            route = decision.route).toList()
    }

    private suspend fun nextCall(): BoundaryCall = withTimeout(WAIT) {
        dispatched.receive().also { it.readPaused.await() }
    }

    private suspend fun successfulExtraction(persona: String = PERSONA, source: String = SOURCE) {
        val task = startExtraction(persona, source)
        val call = nextCall()
        assertEquals(0f, call.requestBody.temperature, 0f)
        assertEquals(256, call.requestBody.maxTokens)
        assertTrue(call.requestBody.messages.last().content.contains(CONTENT))
        call.finish()
        withTimeout(WAIT) { task.await() }.getOrThrow()
        val candidate = repository.observe(persona).first().single()
        assertEquals(CONTENT, candidate.content)
        assertEquals(source, candidate.sourceMessageId)
        assertEquals(auth.currentRepoId, candidate.ownerId)
        assertEquals("PENDING", candidate.status)
        assertTrue(candidate.extractorVersion.contains("cloud-proxy-configured-model"))
        assertEquals(0, rowCount("status = 'RUNNING'"))
        assertTrue(memories.selectForPrompt(persona, false).isEmpty())
    }

    private suspend fun invalidateInFlight(change: suspend () -> Unit) {
        val task = startExtraction()
        val call = nextCall()
        assertEquals(1, rowCount("status = 'RUNNING'"))
        change()
        // Do not release output to cause completion: cancellation must terminate the blocked read itself.
        assertFailed(task)
        assertCanceled(call)
        call.deliverLate()
        assertNoRunningOrPending()
        assertTrue(db.memoryDao().selectForPrompt(OWNER, PERSONA, false, System.currentTimeMillis()).isEmpty())
    }

    private suspend fun assertFailed(task: Deferred<Result<Unit>>) {
        assertTrue("Extraction unexpectedly succeeded", withTimeout(WAIT) { task.await() }.isFailure)
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        val result = withTimeout(WAIT) { runCatching { block() } }
        assertTrue("Expected a boundary rejection", result.isFailure)
        assertFalse("Rejection timed out", result.exceptionOrNull() is TimeoutCancellationException)
    }

    private fun assertCanceled(call: BoundaryCall) {
        assertTrue("Transport was not canceled", call.isCanceled())
        assertTrue("SSE reader was not closed before cleanup finished", call.readerClosed.isCompleted)
    }

    private suspend fun assertNoRunningOrPending() {
        assertEquals(0, rowCount("status IN ('RUNNING', 'PENDING')"))
        assertEquals(0, rowCount("status IN ('FAILED', 'INVALID', 'IGNORED') AND content != ''"))
        assertTrue(db.memoryCurationDao().observe(OWNER, PERSONA).first().isEmpty())
    }

    private suspend fun rowCount(predicate: String): Int = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memory_candidates WHERE $predicate")
            .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
    }

    private companion object {
        const val WAIT = 10_000L
        const val OWNER = "p3-boundary-owner"
        const val PERSONA = "p3-boundary-persona"
        const val SOURCE = "p3-boundary-source"
        const val CONTENT = "我喜欢简短回答"
    }

    // Mirrors M5ControlledCloudApi's Call contract, with a bounded pause inside the real SSE reader.
    private class BoundaryCall(val requestBody: ChatRequest) : Call<ResponseBody> {
        val readPaused = CompletableDeferred<Unit>()
        val readerClosed = CompletableDeferred<Unit>()
        private val executed = AtomicBoolean()
        private val canceled = AtomicBoolean()
        private val closed = AtomicBoolean()
        private val frames = LinkedBlockingQueue<String>()
        private val pending = Buffer()
        private lateinit var callback: Callback<ResponseBody>
        private val beginning = "{\"suggestions\":["
        private val ending = "{\"type\":\"PREFERENCE\",\"content\":\"$CONTENT\"}]}"
        private val mediaType = "text/event-stream; charset=utf-8".toMediaType()
        private val callTimeout = Timeout()
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0L
                if (pending.size == 0L) {
                    if (closed.get()) return -1L
                    if (frames.isEmpty()) readPaused.complete(Unit)
                    val frame = frames.poll(WAIT, TimeUnit.MILLISECONDS)
                        ?: throw IOException("Synthetic SSE release timed out")
                    if (frame == EOF) return -1L
                    pending.writeUtf8(frame)
                }
                return pending.read(sink, minOf(byteCount, pending.size))
            }

            override fun timeout(): Timeout = callTimeout
            override fun close() {
                closed.set(true)
                frames.offer(EOF)
                readerClosed.complete(Unit)
            }
        }.buffer()

        init { frames.add(delta(beginning)) }

        private fun delta(content: String): String = "data: " + Gson().toJson(mapOf(
            "choices" to listOf(mapOf("delta" to mapOf("content" to content))))) + "\n\n"

        override fun execute(): Response<ResponseBody> {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            if (canceled.get()) throw IOException("Canceled")
            return Response.success(object : ResponseBody() {
                override fun contentType() = mediaType
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = buffered
            })
        }

        override fun enqueue(callback: Callback<ResponseBody>) {
            this.callback = callback
            val response = try {
                execute()
            } catch (error: IOException) {
                callback.onFailure(this, error)
                return
            }
            callback.onResponse(this, response)
        }

        fun finish() {
            frames.offer(delta(ending) + "data: [DONE]\n\n")
            frames.offer(EOF)
        }

        // A deliberately late callback must be rejected by CloudChatRepository's real identity/active gate.
        fun deliverLate() {
            check(canceled.get())
            callback.onResponse(this, Response.success(
                (delta(beginning + ending) + "data: [DONE]\n\n").toResponseBody(mediaType)))
        }

        override fun cancel() {
            canceled.set(true)
            frames.offer(EOF)
        }
        override fun isCanceled(): Boolean = canceled.get()
        override fun isExecuted(): Boolean = executed.get()
        override fun clone(): Call<ResponseBody> = BoundaryCall(requestBody)
        override fun request(): Request = Request.Builder()
            .url("https://p3-boundaries.invalid/api/chat/completions").build()
        override fun timeout(): Timeout = callTimeout

        private companion object { const val EOF = "\u0000" }
    }
}
