package com.example.persona.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomMemoryCurationRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.di.DatabaseModule
import com.example.persona.domain.model.MemoryType
import com.example.persona.core.ai.*
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.repository.CloudChatRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(AndroidJUnit4::class)
class MemoryCurationFlowInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "p3-flow-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomMemoryCurationRepository
    private lateinit var memories: RoomMemoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() = runBlocking {
        org.junit.Assume.assumeTrue("P3 flow requires the isolated validation package",
            context.packageName == "com.example.persona.validation")
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi::class.java)
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-$key", mode)
        }
        val auth = AuthManager(api, SessionTokenStore(scoped), db.personaDao())
        check(auth.identity.value.ownerId == "OFFLINE_USER")
        memories = RoomMemoryRepository(db.memoryDao(), auth)
        val validator = LocalModelValidator(AndroidSymlinkChecker)
        val manager = LocalModelManager(scoped,
            validator, ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker))
        val blocked = Proxy.newProxyInstance(CloudChatApi::class.java.classLoader,
            arrayOf(CloudChatApi::class.java)) { _, _, _ -> error("Real network is forbidden in the P3 storage flow") }
        val cloud = CloudChatRepository(CloudChatApi::class.java.cast(blocked),
            BackendConfig("https://example.invalid/", "synthetic-no-network", false), auth)
        val engine = object : LocalAiEngine {
            override val state = MutableStateFlow<EngineState>(EngineState.Idle)
            override suspend fun initialize(model: InstalledModel): Boolean = error("Model initialization is forbidden")
            override suspend fun smokeTest(model: InstalledModel): Boolean = error("Model execution is forbidden")
            override fun streamResponse(session: GenerationSession, prompt: String, history: List<ChatMessage>,
                params: GenerationParams): Flow<GenerationEvent> = error("Model execution is forbidden")
            override fun stopGeneration(session: GenerationSession) = Unit
            override fun release() = Unit
        }
        val ai = HybridAiRepository(cloud, engine, manager, HybridRouter(), { NetworkState.OFFLINE }, scope)
        repository = RoomMemoryCurationRepository(db, auth, memories, ai)
        db.personaDao().insertPersona(PersonaEntity("p", "fixture", "", "", "", "OFFLINE_USER"))
        db.messageDao().insertMessage(MessageEntity("s", "p", "OFFLINE_USER", "请记住：我喜欢简短回答", true, 1))
        repository.configure(true, false)
    }

    @After fun cleanup() {
        scope.cancel()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
    }

    @Test fun confirmationCompletesWithoutReenteringIdentityLockAndIsIdempotent() = runBlocking {
        repository.propose("p", "s")
        val candidate = repository.observe("p").first().single()
        val saved = withTimeout(5000) {
            repository.confirm(candidate.id, "我喜欢简短回答", MemoryType.PREFERENCE)
        }
        val retried = repository.confirm(candidate.id, "ignored duplicate input", MemoryType.FACT)
        assertEquals(saved.id, retried.id)
        assertEquals("我喜欢简短回答", retried.content)
        assertFalse(saved.cloudAllowed)
        assertEquals("p", saved.personaId)
        assertTrue(repository.observe("p").first().isEmpty())
        assertEquals(1, db.memoryDao().selectForPrompt("OFFLINE_USER", "p", false, System.currentTimeMillis()).size)
    }

    @Test fun ignoredAndDeletedCandidatesRequireExplicitUserSelectionToReopen() = runBlocking {
        repository.propose("p", "s")
        val id = repository.observe("p").first().single().id
        repository.ignore(id)
        repository.propose("p", "s", explicitOnly = true)
        assertTrue(repository.observe("p").first().isEmpty())
        repository.propose("p", "s")
        assertEquals(id, repository.observe("p").first().single().id)
        val saved = repository.confirm(id, "我喜欢简短回答", MemoryType.PREFERENCE)
        memories.delete(saved.id, saved.revision)
        repository.propose("p", "s", explicitOnly = true)
        assertTrue(repository.observe("p").first().isEmpty())
    }

    @Test fun automaticCandidatesHonorHistoryExclusionWhileManualSelectionRemainsExplicit() = runBlocking {
        repository.propose("p", "s", explicitOnly = true)
        val candidate = repository.observe("p").first().single()
        assertFalse(candidate.manual)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET excludeFromHistory=1 WHERE id='s'")
        assertTrue(repository.observe("p").first().isEmpty())
        repository.propose("p", "s", explicitOnly = true)
        assertTrue(repository.observe("p").first().isEmpty())
        repository.propose("p", "s")
        assertTrue(repository.observe("p").first().single().manual)
    }

    @Test fun changingCloudExtractionConsentPreservesExistingLocalCandidates() = runBlocking {
        repository.propose("p", "s")
        val before = repository.observe("p").first().single().id
        repository.configure(true, true)
        repository.configure(true, false)
        assertEquals(before, repository.observe("p").first().single().id)
    }

    @Test fun sourceChangeAndDisabledFeatureRejectLateConfirmation() = runBlocking {
        repository.propose("p", "s")
        val id = repository.observe("p").first().single().id
        db.messageDao().updateMessageContent("s", "OFFLINE_USER", "修改了原文", "NORMAL")
        assertTrue(runCatching { repository.confirm(id, "旧候选", MemoryType.FACT) }.isFailure)
        repository.propose("p", "s")
        val next = repository.observe("p").first().single().id
        repository.configure(false, false)
        assertTrue(runCatching { repository.confirm(next, "候选", MemoryType.FACT) }.isFailure)
    }

    @Test fun summariesHaveMultipleSourcesAndInvalidationStopsTheirRetrieval() = runBlocking {
        db.messageDao().insertMessage(MessageEntity("second", "p", "OFFLINE_USER", "我正在学习 Kotlin", true, 2))
        val summary = requireNotNull(repository.summarize("p"))
        assertEquals(setOf("s", "second"), repository.summarySources(summary.id).toSet())
        assertFalse(summary.cloudAllowed)
        assertTrue(memories.selectForPrompt("p", true).isEmpty())
        db.messageDao().updateMessageContent("second", "OFFLINE_USER", "旧目标已结束", "NORMAL")
        assertTrue(memories.selectForPrompt("p", false).isEmpty())
    }
}
