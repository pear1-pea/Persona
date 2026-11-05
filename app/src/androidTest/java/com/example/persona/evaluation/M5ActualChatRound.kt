package com.example.persona.evaluation

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.data.repository.RoomChatRepository
import com.example.persona.data.repository.RoomMemoryRepository
import com.example.persona.data.repository.RoomPersonaRepository
import com.example.persona.di.DatabaseModule
import com.example.persona.domain.model.MessageStatus
import com.example.persona.features.chat.ChatViewModel
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.*

internal class M5ActualChatRound(private val instrumentation: Instrumentation) {
    suspend fun run(case: M5LlmCase, hybrid: HybridAiRepository, cloudAuth: AuthManager? = null): Map<String, Any?> {
        require(!case.cloud || cloudAuth?.identity?.value?.token != null) { "Real cloud authentication is required" }
        val context = instrumentation.targetContext
        val scoped = FixtureContext(context)
        val name = "m5-llm-${UUID.randomUUID()}"
        val store = SessionTokenStore(scoped)
        var database: AppDatabase? = null
        val models = ViewModelStore()
        var job: Job? = null
        fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val started = SystemClock.elapsedRealtime()
        try {
            if (cloudAuth == null) store.save(StoredSession("synthetic-model-evaluation-token", case.owner, "fixture@example.invalid"))
            val owner = cloudAuth?.identity?.value?.ownerId ?: case.owner
            database = open()
            database.personaDao().insertPersona(PersonaEntity(case.persona, case.name, "", "",
                case.backstory, owner, false))
            val snapshots = case.memories.groupBy { it.owner to it.id }.values.map { versions ->
                versions.maxBy { it.revision }
            }
            val now = System.currentTimeMillis()
            for (memory in snapshots) {
                database.memoryDao().insert(MemoryEntity(memory.physicalId,
                    if (memory.owner == case.owner) owner else memory.owner, memory.persona,
                    memory.type, if (memory.deleted) "" else memory.content, memory.sourceMessageId,
                    M5Fixtures.relativeTime(memory.createdAt, case.asOf, now),
                    M5Fixtures.relativeTime(memory.updatedAt, case.asOf, now),
                    memory.expiresAt?.let { M5Fixtures.relativeTime(it, case.asOf, now) },
                    memory.cloudAllowed, memory.revision, memory.deleted))
            }
            for ((index, message) in case.messages.dropLast(1).withIndex()) {
                database.messageDao().insertMessage(MessageEntity("$name-history-$index", case.persona,
                    owner, message.second, message.first == "user", now - 10_000L + index,
                    "NORMAL", localOnly = true))
            }
            database.close()
            // Reopen the real disk database before assembling a fresh conversation.
            database = open()
            val current = database
            val auth = cloudAuth ?: AuthManager(blockedApi(), store, current.personaDao())
            check(auth.identity.value.ownerId == owner)
            val chat = RoomChatRepository(current.messageDao(), auth)
            val memories = RoomMemoryRepository(current.memoryDao(), auth)
            val evidence = memories.selectForPrompt(case.persona, case.cloud, case.messages.last().second.trim())
            val refs = snapshots.associate { (it.physicalId to it.revision) to it.ref }
            val authorized = evidence.map { checkNotNull(refs[it.id to it.revision]) }
            lateinit var model: ChatViewModel
            instrumentation.runOnMainSync {
                model = ChatViewModel(hybrid, RoomPersonaRepository(current.personaDao(), auth), chat, auth, memories)
                models.put("llm-evaluation", model)
                job = model.viewModelScope.coroutineContext.job
                model.loadPersonaInfo(case.persona)
            }
            withTimeout(15_000L) { while (model.currentPersona.value?.id != case.persona) delay(50L) }
            var timedOut = false
            instrumentation.runOnMainSync {
                model.sendMessage((if (case.cloud) "@cloud " else "") + case.messages.last().second, localOnly = !case.cloud)
            }
            try {
                withTimeout(180_000L) { while (model.isGenerating.value) delay(100L) }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                instrumentation.runOnMainSync { model.stopGenerating() }
                withTimeout(15_000L) { while (model.isGenerating.value) delay(50L) }
            }
            val reply = chat.getRecentMessages(case.persona, 2).lastOrNull { !it.isFromUser }
            val completed = !timedOut && reply?.status == MessageStatus.NORMAL && reply.content.isNotBlank()
            check(hybrid.lastRoutingDecision.value?.cloudAllowed == case.cloud)
            return mapOf("status" to if (completed) "completed" else "failed",
                "response" to if (completed) reply?.content else null,
                "partial_response" to if (completed) null else reply?.content,
                "failure_class" to if (completed) null else if (timedOut) "GenerationTimeout" else "NonNormalReply",
                "terminal_status" to reply?.status?.name, "authorized_evidence_refs" to authorized,
                "duration_ms" to SystemClock.elapsedRealtime() - started,
                "executor" to hybrid.activeMode.value.name, "cloud_allowed" to case.cloud,
                "owner_fixture_bound_to_authenticated_test_account" to (owner != case.owner),
                "disk_reopened_before_generation" to true)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            job?.join()
            database?.close()
            context.deleteDatabase(name)
            store.clear()
            scoped.clear()
        }
    }

    private fun blockedApi(): AuthApi = checkNotNull(AuthApi::class.java.cast(Proxy.newProxyInstance(
        AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)
    ) { _, _, _ -> error("Real authentication is forbidden in synthetic model evaluation") }))

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val prefix = "m5-llm-${UUID.randomUUID()}-"
        private val preferences = mutableListOf<SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode).also { preferences += it }
        fun clear() { preferences.forEach { check(it.edit().clear().commit()) } }
    }
}
