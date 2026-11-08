package com.example.persona.data.repository

import androidx.room.withTransaction
import com.example.persona.core.ai.GenerationContext
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.StopReason
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.memory.MemoryExtraction
import com.example.persona.core.memory.MemoryExtractionRunner
import com.example.persona.core.memory.QualityUpgradePermission
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.entity.MemoryCandidateEntity
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.MemoryPreferencesEntity
import com.example.persona.data.local.entity.MemorySummarySourceEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryCandidate
import com.example.persona.domain.model.MemoryPreferences
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.MemoryCurationRepository
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class RoomMemoryCurationRepository @Inject constructor(
    private val database: AppDatabase,
    private val auth: AuthManager,
    private val memories: RoomMemoryRepository,
    private val ai: HybridAiRepository
) : MemoryCurationRepository {
    private val activeTasks = mutableSetOf<String>()
    private val dao get() = database.memoryCurationDao()

    override fun observe(personaId: String): Flow<List<MemoryCandidate>> {
        require(personaId.isNotBlank())
        val identity = auth.identity.value
        return channelFlow {
            auth.withIdentity(identity) {
                dao.observe(identity.ownerId, personaId).collect { rows ->
                    auth.requireIdentity(identity)
                    send(rows.map { row -> MemoryCandidate(row.id, row.ownerId, row.personaId,
                        row.sourceMessageId, row.sourceHash, row.content, MemoryType.valueOf(row.type),
                        row.topicKey, row.status, row.memoryId, row.extractorVersion, row.manual, row.createdAt) })
                }
            }
        }
    }

    override suspend fun preferences(): MemoryPreferences = auth.withIdentity {
        auth.withLocalIdentity { owner ->
            dao.recoverTasks(owner, synchronized(activeTasks) { activeTasks.toList() })
            dao.preferences(owner).let {
                MemoryPreferences(it?.enabled == true, it?.cloudExtractionAllowed == true)
            }
        }
    }

    override suspend fun configure(enabled: Boolean, cloudExtractionAllowed: Boolean) = auth.withIdentity {
        auth.withLocalIdentity { owner ->
            database.withTransaction {
                val revision = (dao.preferences(owner)?.revision ?: 0L) + 1L
                dao.configure(MemoryPreferencesEntity(owner, enabled, enabled && cloudExtractionAllowed, revision))
                if (!enabled) dao.invalidatePending(owner)
            }
        }
    }

    override suspend fun propose(personaId: String, sourceMessageId: String, explicitOnly: Boolean) = auth.withIdentity {
        val owner = auth.requestIdentity().ownerId
        if (dao.preferences(owner)?.enabled != true) return@withIdentity
        val source = source(owner, personaId, sourceMessageId, manual = true)
        if (explicitOnly && source.excludeFromHistory) return@withIdentity
        val explicit = MemoryExtraction.explicit(source.content)
        val suggestions = if (explicitOnly) explicit else explicit.ifEmpty { MemoryExtraction.selected(source.content) }
        persist(source, suggestions, manual = !explicitOnly, version = MemoryExtraction.VERSION, reopen = !explicitOnly)
    }

    override suspend fun proposeWithModel(personaId: String, sourceMessageId: String, cloud: Boolean,
        allowQualityUpgrade: Boolean) = auth.withIdentity {
        val owner = auth.requestIdentity().ownerId
        val consent = requireNotNull(dao.preferences(owner))
        check(consent.enabled && (!cloud || consent.cloudExtractionAllowed)) { "Extraction consent is required" }
        val original = source(owner, personaId, sourceMessageId, manual = true)
        check(!cloud || !original.localOnly) { "Source is restricted to local processing" }
        val taskId = UUID.randomUUID().toString()
        auth.withLocalIdentity {
            dao.insert(MemoryCandidateEntity(taskId, owner, personaId, sourceMessageId, hash(original.content),
                "", "FACT", "", MemoryExtraction.VERSION, true, "RUNNING", null, System.currentTimeMillis()))
            synchronized(activeTasks) { activeTasks.add(taskId) }
        }
        var session: GenerationSession? = null
        var attemptCloud = cloud
        var modelIdentity: String? = null
        suspend fun authorize(useCloud: Boolean): MessageEntity {
            val current = source(owner, personaId, sourceMessageId, manual = true)
            check(hash(current.content) == hash(original.content)) { "Source changed" }
            check(dao.candidate(owner, taskId)?.status == "RUNNING") { "Extraction task was invalidated" }
            val currentConsent = requireNotNull(dao.preferences(owner))
            check(currentConsent.revision == consent.revision && currentConsent.enabled &&
                (!useCloud || currentConsent.cloudExtractionAllowed)) { "Extraction consent changed" }
            check(!useCloud || !current.localOnly) { "Source is restricted to local processing" }
            return current
        }
        try {
            val result = withTimeout(60_000L) {
                coroutineScope {
                    val generation = this
                    val observer = launch {
                        combine(dao.observePreferences(owner), dao.observeSource(owner, sourceMessageId),
                            dao.observeTask(owner, taskId)) { p, row, task ->
                            task?.status == "RUNNING" && p?.revision == consent.revision && p.enabled &&
                                (!attemptCloud || p.cloudExtractionAllowed) && row != null &&
                                row.status == "NORMAL" && row.isFromUser && hash(row.content) == hash(original.content) &&
                                (!attemptCloud || !row.localOnly)
                        }.collect { valid ->
                            if (!valid) generation.cancel("Extraction source or consent changed")
                        }
                    }
                    try {
                        MemoryExtractionRunner.run(original.content, cloud, permission = {
                            val current = authorize(false)
                            QualityUpgradePermission(allowQualityUpgrade, consent.cloudExtractionAllowed,
                                current.localOnly, ai.memoryUpgradeNetwork(), session?.stopReason != null)
                        }, generate = { useCloud, automatic ->
                            attemptCloud = useCloud
                            authorize(useCloud)
                            val currentSession = GenerationSession(taskId).also { session = it }
                            modelIdentity = if (useCloud) "cloud-proxy-configured-model" else null
                            currentSession.bindContextProvider {
                                val current = authorize(useCloud)
                                if (!useCloud) {
                                    val currentModel = requireNotNull(ai.localModelIdentity)
                                    if (modelIdentity == null) modelIdentity = currentModel
                                    check(currentModel == modelIdentity) { "Extraction model changed" }
                                }
                                GenerationContext("只从用户原文提取可确认资料，输出 JSON。", emptyList(),
                                    MemoryExtraction.prompt(current.content))
                            }
                            val output = StringBuilder()
                            var completed = false
                            ai.streamMemoryExtraction(currentSession, useCloud, "只从用户原文提取可确认资料，输出 JSON。",
                                MemoryExtraction.prompt(original.content), GenerationParams(temperature = 0f, maxTokens = 256),
                                automaticCloud = automatic).collect { event ->
                                when (event) {
                                    is GenerationEvent.Delta -> {
                                        check(output.length + event.text.length <= 6000) { "Extraction output is too long" }
                                        output.append(event.text)
                                    }
                                    is GenerationEvent.Completed -> completed = true
                                    is GenerationEvent.Failed -> error("Extraction failed: ${event.error.type}")
                                    is GenerationEvent.Stopped -> error("Extraction stopped")
                                }
                            }
                            check(completed && currentSession.stopReason == null) { "Extraction did not complete" }
                            if (!useCloud) check(ai.localModelIdentity == modelIdentity) { "Extraction model changed" }
                            output.toString()
                        }, contract = { _, accepted ->
                            ai.recordExtractionContract(checkNotNull(session), accepted)
                        })
                    } finally {
                        observer.cancel()
                    }
                }
            }
            authorize(result.usedCloud)
            persist(original, result.suggestions, true,
                "${MemoryExtraction.VERSION}:$modelIdentity:t0:p0.8:max256:upgrade=${result.upgraded}",
                taskId, consent.revision, reopen = true)
        } finally {
            try {
                session?.let {
                    it.requestStop(StopReason.USER_REQUESTED)
                    ai.stopGeneration(it)
                }
            } finally {
                try {
                    withContext(NonCancellable) { dao.finishTask(owner, taskId, "FAILED") }
                } finally {
                    synchronized(activeTasks) { activeTasks.remove(taskId) }
                }
            }
        }
    }

    private suspend fun source(owner: String, persona: String, id: String, manual: Boolean): MessageEntity {
        val row = requireNotNull(database.messageDao().getMessageById(id, owner)) { "Source is unavailable" }
        require(row.personaId == persona && row.isFromUser && row.status == "NORMAL")
        require(manual || !row.excludeFromHistory) { "Source is excluded from automatic extraction" }
        require(row.content.isNotBlank() && row.content.length <= 4000)
        return row
    }

    private suspend fun persist(source: MessageEntity, suggestions: List<MemoryExtraction.Suggestion>, manual: Boolean, version: String, taskId: String? = null, consentRevision: Long? = null, reopen: Boolean = false) {
        auth.withLocalIdentity { owner ->
            database.withTransaction {
                val consent = requireNotNull(dao.preferences(owner))
                check(consent.enabled && (consentRevision == null || consent.revision == consentRevision))
                if (taskId != null) check(dao.candidate(owner, taskId)?.status == "RUNNING")
                val current = source(owner, source.personaId, source.id, manual)
                check(hash(current.content) == hash(source.content)) { "Source changed" }
                for (suggestion in suggestions) {
                    val id = hash(listOf(owner, source.personaId, source.id, hash(source.content),
                        suggestion.type.name, suggestion.content).joinToString("\u0000"))
                    dao.insert(MemoryCandidateEntity(id, owner, source.personaId, source.id,
                        hash(source.content), suggestion.content, suggestion.type.name, suggestion.topicKey,
                        version, manual, "PENDING", null, System.currentTimeMillis()))
                    if (reopen) dao.reopen(owner, id, suggestion.content)
                }
                if (taskId != null) check(dao.finishTask(owner, taskId, "DONE") == 1)
            }
        }
    }

    override suspend fun ignore(id: String) = auth.withIdentity {
        auth.withLocalIdentity { owner ->
            database.withTransaction {
                val candidate = requireNotNull(dao.candidate(owner, id))
                check(dao.finish(owner, id, "IGNORED") == 1) { "Candidate changed" }
                dao.invalidateTasksForSource(owner, candidate.sourceMessageId)
            }
        }
    }

    override suspend fun conflicts(id: String): List<Memory> = auth.withIdentity {
        val owner = auth.requestIdentity().ownerId
        val candidate = requireNotNull(dao.candidate(owner, id))
        dao.memories(owner, candidate.personaId, System.currentTimeMillis())
            .filter { it.type == candidate.type && !it.id.startsWith("summary-") }.take(12).map { row ->
                Memory(row.id, row.ownerId, row.personaId, MemoryType.valueOf(row.type), row.content,
                    row.sourceMessageId, row.createdAt, row.updatedAt, row.expiresAt,
                    row.cloudAllowed, row.revision, row.deleted)
            }
    }

    override suspend fun confirm(id: String, content: String, type: MemoryType, shared: Boolean,
        cloudAllowed: Boolean, expiresAt: Long?, replaceId: String?, expectedRevision: Int?): Memory = auth.withIdentity {
        require(content.isNotBlank() && content.length <= 400)
        require((replaceId == null) == (expectedRevision == null))
        require(expiresAt == null || expiresAt > System.currentTimeMillis())
        withContext(NonCancellable) {
            auth.withLocalIdentity { owner ->
                database.withTransaction {
                    val candidate = requireNotNull(dao.candidate(owner, id)) { "Candidate is unavailable" }
                    if (candidate.status == "ACCEPTED") {
                        val existing = requireNotNull(database.memoryDao().findOwned(requireNotNull(candidate.memoryId), owner))
                        check(!existing.deleted) { "Memory was deleted" }
                        return@withTransaction existing.toMemory()
                    }
                    check(candidate.status == "PENDING" && dao.preferences(owner)?.enabled == true)
                    val source = source(owner, candidate.personaId, candidate.sourceMessageId, candidate.manual)
                    check(hash(source.content) == candidate.sourceHash) { "Source changed" }
                    val target = replaceId?.let { requireNotNull(database.memoryDao().findOwned(it, owner)) }
                    check(target == null || (!target.deleted && target.revision == expectedRevision &&
                        target.personaId == (if (shared) null else candidate.personaId))) { "Replacement scope or revision changed" }
                    val existing = dao.memories(owner, candidate.personaId, System.currentTimeMillis()).firstOrNull {
                        it.content == content && it.type == type.name &&
                            it.personaId == (if (shared) null else candidate.personaId) &&
                            it.cloudAllowed == cloudAllowed && it.expiresAt == expiresAt
                    }
                    val memoryId = target?.id ?: existing?.id ?: UUID.randomUUID().toString()
                    // Claim before saveMemory changes history eligibility. The encompassing transaction rolls back both.
                    check(dao.finish(owner, id, "ACCEPTED", memoryId) == 1)
                    if (existing != null && target == null) return@withTransaction existing.toMemory()
                    memories.saveWithinIdentity(owner, if (shared) null else candidate.personaId, type, content, cloudAllowed,
                        candidate.sourceMessageId, target?.id, target?.revision, expiresAt).also { saved ->
                        // New IDs are assigned by MemoryRepository, so bind the committed result.
                        dao.bindAcceptedMemory(owner, id, saved.id)
                    }
                }
            }
        }
    }

    override suspend fun summarize(personaId: String): Memory? = auth.withIdentity {
        auth.withLocalIdentity { owner ->
            database.withTransaction {
                check(dao.preferences(owner)?.enabled == true)
                val sources = dao.summaryMessages(owner, personaId).asReversed()
                val result = com.example.persona.core.memory.MemorySummary.summarize(sources.map { row ->
                    Message(row.id, row.personaId, row.content, row.isFromUser, row.timestamp,
                        MessageStatus.valueOf(row.status), row.localOnly, row.excludeFromHistory)
                })
                if (result.sources.isEmpty()) return@withTransaction null
                val id = "summary-" + hash(owner + "\u0000" + personaId + "\u0000" + result.sourceHash)
                database.memoryDao().findOwned(id, owner)?.let { previous ->
                    check(!previous.deleted) { "Summary was deleted; select sources explicitly to save again" }
                    return@withTransaction previous.toMemory()
                }
                val now = System.currentTimeMillis()
                // Summaries preserve excerpts and source eligibility; they remain local-only.
                val summary = MemoryEntity(id, owner, personaId, "FACT", result.text,
                    createdAt = now, updatedAt = now)
                database.memoryDao().insert(summary)
                dao.addSummarySources(result.sources.map {
                    MemorySummarySourceEntity(id, it.messageId, owner, it.contentHash)
                })
                memories.notifyDerivedChange()
                summary.toMemory()
            }
        }
    }

    override suspend fun summarySources(memoryId: String): List<String> = auth.withIdentity {
        val owner = auth.requestIdentity().ownerId
        val memory = requireNotNull(database.memoryDao().findOwned(memoryId, owner))
        check(!memory.deleted)
        dao.summarySources(owner, memoryId).map { it.sourceMessageId }
    }

    private fun MemoryEntity.toMemory() = Memory(id, ownerId, personaId, MemoryType.valueOf(type), content,
        sourceMessageId, createdAt, updatedAt, expiresAt, cloudAllowed, revision, deleted)

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
