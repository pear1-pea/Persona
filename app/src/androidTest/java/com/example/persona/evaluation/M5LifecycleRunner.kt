package com.example.persona.evaluation

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.persona.core.ai.*
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.repository.*
import com.example.persona.di.DatabaseModule
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.MemoryRepository
import com.example.persona.features.chat.ChatViewModel
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

internal class M5LifecycleRunner(private val instrumentation: Instrumentation) {
    suspend fun observe(context: Context, case: M5Case, policyNetwork: NetworkState? = null,
        meteredDenial: Boolean = false): Map<String, Any?> {
        case.lifecycleUnsupportedReason()?.let { return unsupported(case, it) }
        val fallback = case.events.any { it.action == M5LifecycleAction.EXECUTOR }
        val resend = case.events.any { it.action == M5LifecycleAction.RESEND }
        val network = RoutingContextProvider(context)
        val readNetwork: () -> NetworkState = policyNetwork?.let { value -> { value } } ?: network::networkState
        if (fallback && !meteredDenial && readNetwork() != NetworkState.UNMETERED) {
            return unsupported(case, "Device must have an unmetered network for the application's fallback policy")
        }
        val scoped = FixtureContext(context)
        val store = SessionTokenStore(scoped)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val viewModels = ViewModelStore()
        var viewModelJob: Job? = null
        var hybrid: HybridAiRepository? = null
        try {
            store.save(StoredSession("synthetic-lifecycle-token", case.owner, "fixture@example.invalid"))
            val auth = AuthManager(blockedAuthApi(), store, database.personaDao())
            check(auth.identity.value.ownerId == case.owner)
            val dao = database.memoryDao()
            val snapshots = case.memories.groupBy { it.owner to it.id }.values.map { rows -> rows.maxBy { it.revision } }
            val refs = ConcurrentHashMap<Pair<String, Int>, String>()
            val now = System.currentTimeMillis()
            for (memory in snapshots) {
                refs[memory.physicalId to memory.revision] = memory.ref
                dao.insert(MemoryEntity(memory.physicalId, memory.owner, memory.persona, memory.type, memory.content,
                    memory.sourceMessageId, M5Fixtures.relativeTime(memory.createdAt, case.asOf, now),
                    M5Fixtures.relativeTime(memory.updatedAt, case.asOf, now),
                    memory.expiresAt?.let { M5Fixtures.relativeTime(it, case.asOf, now) },
                    memory.cloudAllowed, memory.revision, memory.deleted))
            }
            val candidates = dao.selectForPrompt(case.owner, case.persona, case.cloud, System.currentTimeMillis())
                .map { checkNotNull(refs[it.id to it.revision]) }
            database.personaDao().insertPersona(PersonaEntity(case.persona, "岚", "", "",
                "你是虚构的书屋管理员，用简洁自然的中文回答。", case.owner, false))
            val memories = AuditMemory(RoomMemoryRepository(dao, auth))
            val gate = M5LifecycleGate(RoomChatRepository(database.messageDao(), auth))
            val traces = CopyOnWriteArrayList<Map<String, Any?>>()
            val attemptedDispatches = AtomicInteger()
            fun record(dispatch: M5Dispatch) {
                attemptedDispatches.incrementAndGet()
                val cloud = dispatch.backend == Backend.CLOUD
                val selected = checkNotNull(memories.latest[cloud])
                check(facts(dispatch.userBody, case.question) == selected.map { it.content }) { "Executor evidence mismatch" }
                check(dispatch.messages.none { it.content.contains("SYNTHETIC_LOCAL_PARTIAL") }) {
                    "Partial local output reached a new executor context"
                }
                if (cloud) check(case.memories.filterNot { it.cloudAllowed }.none { note ->
                    dispatch.messages.any { it.content.contains(note.content) }
                }) { "Private memory reached a cloud message" }
                traces += mapOf("backend" to dispatch.backend.name, "sessionId" to dispatch.sessionId,
                    "refs" to selected.map { checkNotNull(refs[it.id to it.revision]) })
            }
            val engine = M5ControlledLocalEngine(::record)
            val validator = LocalModelValidator(AndroidSymlinkChecker)
            val installer = ModelPackageInstaller(scoped, validator, AndroidSymlinkChecker)
            val manager = LocalModelManager(scoped, validator, installer)
            val cloud = CloudChatRepository(M5ControlledCloudApi(::record),
                BackendConfig("https://m5-lifecycle.invalid", "synthetic-no-network"), auth)
            val repository = HybridAiRepository(cloud, engine, manager, HybridRouter(), readNetwork, routeScope)
            hybrid = repository
            if (fallback || resend) {
                val profile = manager.deviceCapability()
                if (profile.thermalStatus in setOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL,
                    ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
                    return unsupported(case, "Device thermal admission blocks the controlled local executor")
                }
                val source = M5LifecycleModelFixture.write(scoped.sourceDirectory, "m5-lifecycle-${case.id}")
                val installed = installer.installFromDirectory(source, manager.modelsRoot(), profile)
                check(installed is ModelPackageInstallResult.Success) { "Synthetic fixture failed validation" }
                check(repository.selectLocalModel(installed.model.modelDir) != null)
                check(repository.initializeLocalModel())
            }
            lateinit var model: ChatViewModel
            instrumentation.runOnMainSync {
                model = ChatViewModel(repository, RoomPersonaRepository(database.personaDao(), auth), gate, auth, memories)
                viewModels.put("lifecycle", model)
                viewModelJob = model.viewModelScope.coroutineContext.job
                model.loadPersonaInfo(case.persona)
            }
            await { model.currentPersona.value?.id == case.persona }
            val held = gate.arm()
            fun send() = instrumentation.runOnMainSync {
                model.sendMessage(if (case.cloud) "@cloud ${case.question}" else case.question,
                    localOnly = !case.cloud && !fallback)
            }
            send()
            withTimeout(15_000L) { held.entered.await() }
            val rejected = mutableListOf<String>()
            if (fallback) {
                engine.failNext = true
                held.release.complete(Unit)
            } else {
                for (event in case.events) {
                    if (event.action == M5LifecycleAction.RESEND) continue
                    if (event.action == M5LifecycleAction.CANCEL) {
                        instrumentation.runOnMainSync { model.stopGenerating() }
                        continue
                    }
                    val original = case.memories.single { it.ref == event.target }
                    val row = checkNotNull(dao.findOwned(original.physicalId, case.owner))
                    when (event.action) {
                        M5LifecycleAction.DELETE -> memories.delete(row.id, row.revision)
                        M5LifecycleAction.REVOKE_CLOUD -> memories.save(row.personaId, MemoryType.valueOf(row.type),
                            row.content, false, row.sourceMessageId, row.id, row.revision, row.expiresAt)
                        M5LifecycleAction.REVISE -> {
                            val saved = memories.save(row.personaId, MemoryType.valueOf(row.type),
                                event.replacementContent ?: row.content, row.cloudAllowed, row.sourceMessageId,
                                row.id, row.revision, row.expiresAt)
                            check(saved.revision == event.replacementRevision)
                            refs[saved.id to saved.revision] = checkNotNull(event.replacementRef)
                        }
                        M5LifecycleAction.REPLAY -> {
                            check(row.deleted)
                            val result = runCatching { memories.save(row.personaId, MemoryType.valueOf(row.type),
                                event.replacementContent ?: original.content, original.cloudAllowed,
                                null, row.id, row.revision, row.expiresAt) }
                            check(result.isFailure && dao.findOwned(row.id, case.owner)?.deleted == true)
                            rejected += checkNotNull(event.replacementRef)
                        }
                        else -> error("Unsupported lifecycle schedule")
                    }
                }
                withTimeout(15_000L) {
                    held.finished.await()
                    held.requestJob.await().join()
                }
                check(engine.stoppedSessions.isNotEmpty()) { "Mutation did not stop the real request" }
                check(attemptedDispatches.get() == 0)
                held.release.complete(Unit)
                if (resend) send()
            }
            await { !model.isGenerating.value }
            val expected = if (meteredDenial) listOf(Backend.MNN.name)
                else if (fallback) listOf(Backend.MNN.name, Backend.CLOUD.name)
                else if (resend) listOf(Backend.MNN.name) else emptyList()
            check(traces.map { it["backend"] } == expected && attemptedDispatches.get() == expected.size)
            if (expected.isNotEmpty()) {
                val finalReply = gate.getRecentMessages(case.persona, 2).last()
                val expectedStatus = if (meteredDenial) MessageStatus.FAILED else MessageStatus.NORMAL
                check(!finalReply.isFromUser && finalReply.status == expectedStatus) {
                    "The fresh request did not persist a normal terminal reply"
                }
                if (meteredDenial) {
                    check(repository.lastRoutingDecision.value?.cloudAllowed == false)
                    check(policyNetwork == NetworkState.METERED)
                }
                val stoppedIds = engine.stoppedSessions.map { it.id }.toSet()
                check(traces.filter { it["backend"] == Backend.MNN.name }.none { it["sessionId"] in stoppedIds }) {
                    "A fresh local request was stopped after dispatch"
                }
            }
            val injected = if (traces.isEmpty()) emptyList<String>()
                else (traces.last()["refs"] as List<*>).map { it as String }
            return mapOf("case_id" to case.id, "status" to "observed",
                "boundary_scope" to "chatviewmodel_hybrid_executor",
                "actual" to mapOf("candidates" to candidates, "injected" to injected, "rejected_writes" to rejected),
                "lifecycle" to mapOf("dispatches" to traces, "stoppedRequests" to engine.stoppedSessions.size,
                    "physicalNetworkState" to network.networkState().name,
                    "policyNetworkState" to readNetwork().name, "meteredDenialControl" to meteredDenial,
                    "realModelExecution" to false, "realNetworkExecution" to false))
        } finally {
            withContext(NonCancellable) {
                try {
                    instrumentation.runOnMainSync { viewModels.clear() }
                    viewModelJob?.let { withTimeout(10_000L) { it.join() } }
                } finally {
                  try { hybrid?.clearLocalModel() } finally {
                    routeScope.cancel()
                    database.close()
                    store.clear()
                    scoped.clean()
                  }
                }
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000L) {
        while (!condition()) delay(20L)
    }

    private fun facts(body: String, question: String): List<String> {
        if (body == question) return emptyList()
        check(body.endsWith("\n\n当前用户问题：\n" + question)) { "Unexpected executor user turn" }
        return runCatching { JsonParser().parse(body.substringAfter("已保存资料 JSON：\n")
            .substringBefore("\n\n当前用户问题：\n")).asJsonArray.map { it.asString } }
            .getOrElse { throw IllegalStateException("Malformed executor evidence JSON") }
    }

    private fun unsupported(case: M5Case, reason: String): Map<String, Any?> =
        mapOf("case_id" to case.id, "status" to "unsupported", "actual" to null, "reason" to reason)

    private class AuditMemory(private val actual: MemoryRepository) : MemoryRepository by actual {
        val latest = ConcurrentHashMap<Boolean, List<Memory>>()
        override suspend fun selectForPrompt(personaId: String, cloud: Boolean): List<Memory> =
            actual.selectForPrompt(personaId, cloud).also { latest[cloud] = it }
    }

    private fun blockedAuthApi(): AuthApi = checkNotNull(AuthApi::class.java.cast(Proxy.newProxyInstance(
        AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)
    ) { _, _, _ -> error("Real auth API is forbidden in lifecycle evaluation") }))

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val token = "m5-life-${UUID.randomUUID()}"
        private val preferences = mutableListOf<SharedPreferences>()
        private val external = mutableSetOf<File>()
        val sourceDirectory = File(base.cacheDir, token)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$token-$name", mode).also { preferences += it }
        override fun getExternalFilesDir(type: String?): File =
            File(checkNotNull(super.getExternalFilesDir(type)), token).also { it.mkdirs(); external += it }
        fun clean() {
            preferences.forEach { check(it.edit().clear().commit()) }
            check(!sourceDirectory.exists() || sourceDirectory.deleteRecursively())
            external.forEach { check(it.name == token && it.deleteRecursively()) }
        }
    }
}
