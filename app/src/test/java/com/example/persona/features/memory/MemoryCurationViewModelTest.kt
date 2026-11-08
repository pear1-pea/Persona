package com.example.persona.features.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryCandidate
import com.example.persona.domain.model.MemoryPreferences
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.MemoryCurationRepository
import com.example.persona.domain.repository.MemoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCurationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chat: ChatRepository = mock()
    private val memories: MemoryRepository = mock()
    private val api: AuthApi = mock()
    private val auth = AuthManager(api, mock(), mock())
    private val repository = FakeCuration()
    private val memoryRows = MutableStateFlow<List<Memory>>(emptyList())
    private val handle = SavedStateHandle()
    private val instances = mutableListOf<MemoryCurationViewModel>()
    private lateinit var vm: MemoryCurationViewModel

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        Dispatchers.setMain(dispatcher)
        whenever(chat.getRecentMessages(any(), any())).thenReturn(listOf(message()))
        whenever(chat.getMessageById(any())).thenAnswer { message(it.getArgument(0)) }
        whenever(memories.observe(any())).thenReturn(memoryRows)
        repository.rows.value = listOf(candidate())
        vm = create(handle)
        vm.initialize("persona", "Nova", "source")
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        instances.forEach { clear(it) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun clear(model: MemoryCurationViewModel) = ViewModelStore().apply { put("curation", model); clear() }
    private fun create(state: SavedStateHandle = SavedStateHandle()) =
        MemoryCurationViewModel(repository, memories, chat, auth, state).also(instances::add)
    private fun restored() = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private fun message(id: String = "source") = Message(id, "persona", "I like tea", true)
    private fun candidate(id: String = "candidate", owner: String = auth.identity.value.ownerId) =
        MemoryCandidate(id, owner, "persona", "source", "hash", "Tea", MemoryType.FACT,
            "tea", "PENDING", null, "selected-v1", true, 1L)
    private fun memory(id: String = "memory", revision: Int = 3) = Memory(id, auth.identity.value.ownerId,
        "persona", MemoryType.FACT, "Coffee", createdAt = 1L, updatedAt = 2L, revision = revision)
    private fun enable(cloud: Boolean = false) {
        vm.configure(true, cloud)
        dispatcher.scheduler.runCurrent()
    }
    private fun select() {
        vm.selectCandidate("candidate")
        dispatcher.scheduler.runCurrent()
    }
    private suspend fun signIn(owner: String) {
        whenever(api.login(any())).thenReturn(AuthSessionDto("token-$owner", "2099-01-01T00:00:00Z",
            AuthUserDto(owner, "$owner@test.dev")))
        auth.signIn("$owner@test.dev", "password")
    }

    @Test
    fun `entry restores source selection and never proposes or saves automatically`() = runTest(dispatcher) {
        assertEquals("source", vm.uiState.value.selectedMessageId)
        assertEquals(MemoryPreferences(), vm.uiState.value.preferences)
        vm.proposeSelected()
        vm.extractLocal()
        vm.extractCloud()
        vm.summarize()
        advanceUntilIdle()
        assertTrue(repository.extractions.isEmpty())
        assertTrue(repository.confirmations.isEmpty())
        assertEquals(0, repository.summaries)
    }

    @Test
    fun `selected proposal is explicit and cloud extraction requires independent consent`() = runTest(dispatcher) {
        enable()
        vm.proposeSelected()
        advanceUntilIdle()
        vm.extractLocal()
        advanceUntilIdle()
        vm.extractCloud()
        advanceUntilIdle()
        assertEquals(listOf("selected:persona:source", "local:persona:source"), repository.extractions)
        enable(cloud = true)
        vm.extractCloud()
        advanceUntilIdle()
        assertEquals("cloud:persona:source", repository.extractions.last())
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `cloud extraction never sends local only source`() = runTest(dispatcher) {
        enable(cloud = true)
        whenever(chat.getMessageById("source")).thenReturn(message().copy(localOnly = true))
        vm.extractCloud()
        advanceUntilIdle()
        assertTrue(repository.extractions.isEmpty())
        assertEquals(CurationError.OPERATION, vm.uiState.value.error)
    }

    @Test
    fun `source eligibility is rechecked at extraction time`() = runTest(dispatcher) {
        enable()
        listOf(message().copy(id = "wrong"), message().copy(personaId = "other"),
            message().copy(isFromUser = false), message().copy(status = MessageStatus.FAILED)).forEach {
            whenever(chat.getMessageById("source")).thenReturn(it)
            vm.proposeSelected()
            advanceUntilIdle()
        }
        assertTrue(repository.extractions.isEmpty())
    }

    @Test
    fun `cancel extraction releases UI and keeps captured session`() = runTest(dispatcher) {
        enable()
        val gate = CompletableDeferred<Unit>()
        val captured = auth.identity.value
        var operationIdentity: AuthIdentity? = null
        repository.onExtract = { operationIdentity = currentCoroutineContext()[AuthIdentity]; gate.await() }
        vm.extractLocal()
        vm.extractLocal()
        runCurrent()
        vm.cancelExtraction()
        advanceUntilIdle()
        assertEquals(1, repository.extractions.size)
        assertSame(captured, operationIdentity)
        assertNull(vm.uiState.value.operation)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `clearing VM cancels extraction`() = runTest(dispatcher) {
        enable()
        var cancelled = false
        repository.onExtract = { try { CompletableDeferred<Unit>().await() } finally { cancelled = true } }
        vm.extractLocal()
        runCurrent()
        clear(vm)
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    @Test
    fun `conflicts require explicit new or specific revision choice`() = runTest(dispatcher) {
        enable()
        repository.conflictRows = listOf(memory())
        select()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(repository.confirmations.isEmpty())
        vm.chooseReplacement("memory")
        repository.conflictRows = listOf(memory(revision = 4))
        vm.confirm()
        advanceUntilIdle()
        assertEquals("memory", repository.confirmations.single().replaceId)
        assertEquals(3, repository.confirmations.single().revision)
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `even an empty conflict list requires an explicit create choice`() = runTest(dispatcher) {
        enable()
        select()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(repository.confirmations.isEmpty())
        vm.chooseNew()
        vm.confirm()
        advanceUntilIdle()
        assertNull(repository.confirmations.single().replaceId)
    }

    @Test
    fun `new memory choice preserves independent scope cloud and expiry fields`() = runTest(dispatcher) {
        enable()
        repository.conflictRows = listOf(memory())
        select()
        vm.updateDraft("Updated", MemoryType.GOAL, true, true, "2099-12-31")
        vm.chooseNew()
        vm.confirm()
        advanceUntilIdle()
        val result = repository.confirmations.single()
        assertEquals(Confirmation("candidate", "Updated", MemoryType.GOAL, true, true,
            parseMemoryExpiry("2099-12-31")), result)
    }

    @Test
    fun `replacement metadata cannot be supplied for hidden or mismatched type memories`() = runTest(dispatcher) {
        enable()
        repository.conflictRows = listOf(memory().copy(ownerId = "other"),
            memory("different").copy(type = MemoryType.GOAL), memory("wrong-persona").copy(personaId = "other"))
        select()
        vm.chooseReplacement("memory")
        vm.chooseReplacement("different")
        vm.chooseReplacement("wrong-persona")
        assertNull(vm.uiState.value.draft!!.replaceId)
        assertTrue(vm.uiState.value.possibleConflicts.isEmpty())
    }

    @Test
    fun `edited candidate type displays observed same type conflicts`() = runTest(dispatcher) {
        enable()
        memoryRows.value = listOf(memory("goal").copy(type = MemoryType.GOAL))
        runCurrent()
        select()
        vm.updateDraft("Goal", MemoryType.GOAL, false, false, "")
        assertEquals(listOf("goal"), vm.uiState.value.possibleConflicts.map { it.id })
        assertFalse(vm.uiState.value.draft!!.resolutionChosen)
    }

    @Test
    fun `invalid content and expiry never confirm`() = runTest(dispatcher) {
        enable()
        select()
        listOf("", " ", "x".repeat(401)).forEach {
            vm.updateDraft(it, MemoryType.FACT, false, false, "")
            vm.confirm()
        }
        listOf("2026-02-30", "2026-1-01", "2000-01-01", "nonsense").forEach {
            vm.updateDraft("Tea", MemoryType.FACT, false, false, it)
            vm.confirm()
        }
        advanceUntilIdle()
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `confirmation failure preserves draft and CAS revision`() = runTest(dispatcher) {
        enable()
        repository.conflictRows = listOf(memory())
        select()
        vm.chooseReplacement("memory")
        repository.onConfirm = { error("revision changed") }
        vm.confirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, repository.confirmations.size)
        assertEquals(3, vm.uiState.value.draft!!.expectedRevision)
        assertEquals(CurationError.OPERATION, vm.uiState.value.error)
        assertNull(vm.uiState.value.operation)
    }

    @Test
    fun `recreation restores source draft choice and original revision without reproposing`() = runTest(dispatcher) {
        enable()
        repository.conflictRows = listOf(memory())
        select()
        vm.chooseReplacement("memory")
        vm.updateDraft("Edited", MemoryType.FACT, false, true, "2099-12-31")
        val state = restored()
        repository.conflictRows = listOf(memory(revision = 4))
        val next = create(state)
        next.initialize("persona", "Nova", "different")
        advanceUntilIdle()
        assertEquals("source", next.uiState.value.selectedMessageId)
        assertEquals(vm.uiState.value.draft, next.uiState.value.draft)
        next.confirm()
        advanceUntilIdle()
        assertEquals(3, repository.confirmations.single().revision)
        assertTrue(repository.extractions.isEmpty())
    }

    @Test
    fun `summary result is displayed exclusively from observed summary prefixed rows`() = runTest(dispatcher) {
        enable()
        repository.summaryResult = memory("summary-new")
        vm.summarize()
        advanceUntilIdle()
        assertEquals(1, repository.summaries)
        assertTrue(vm.uiState.value.summaries.isEmpty())
        memoryRows.value = listOf(memory(), memory("summary-new"),
            memory("summary-other").copy(ownerId = "other"), memory("summary-deleted").copy(deleted = true))
        runCurrent()
        assertEquals(listOf("summary-new"), vm.uiState.value.summaries.map { it.id })
    }

    @Test
    fun `summary source viewer resolves every source by owner scoped message lookup`() = runTest(dispatcher) {
        memoryRows.value = listOf(memory("summary-new"))
        repository.sourceIds = listOf("one", "two", "missing", "wrong-id", "failed", "assistant")
        whenever(chat.getMessageById("missing")).thenReturn(null)
        whenever(chat.getMessageById("wrong-id")).thenReturn(message("other"))
        whenever(chat.getMessageById("failed")).thenReturn(message("failed").copy(status = MessageStatus.FAILED))
        whenever(chat.getMessageById("assistant")).thenReturn(message("assistant").copy(isFromUser = false))
        runCurrent()
        vm.showSummarySources("summary-new")
        advanceUntilIdle()
        val sources = vm.uiState.value.sources!!
        assertEquals(repository.sourceIds, sources.map { it.messageId })
        assertEquals(listOf("I like tea", "I like tea", null, null, null, null), sources.map { it.content })
        assertSame(auth.identity.value, repository.sourceIdentity)
    }

    @Test
    fun `candidate source lookup is explicit and hidden candidates are inaccessible`() = runTest(dispatcher) {
        vm.showCandidateSource("hidden")
        advanceUntilIdle()
        assertNull(vm.uiState.value.sources)
        vm.showCandidateSource("candidate")
        advanceUntilIdle()
        assertEquals("source", vm.uiState.value.sources!!.single().messageId)
        assertEquals("I like tea", vm.uiState.value.sources!!.single().content)
        vm.closeSources()
        assertNull(vm.uiState.value.sources)
    }

    @Test
    fun `ignore never confirms and closes selected editor`() = runTest(dispatcher) {
        enable()
        select()
        vm.ignore("candidate")
        advanceUntilIdle()
        assertEquals(listOf("candidate"), repository.ignored)
        assertNull(vm.uiState.value.draft)
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `switching account synchronously clears private UI and blocks stale confirmation`() = runTest(dispatcher) {
        enable()
        select()
        memoryRows.value = listOf(memory("summary-old"))
        runCurrent()
        signIn("new-owner")
        vm.confirm()
        assertNull(vm.uiState.value.draft)
        assertNull(vm.uiState.value.selectedMessageId)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.candidates.isEmpty())
        assertTrue(vm.uiState.value.memories.isEmpty())
        assertFalse(vm.uiState.value.preferences.enabled)
        advanceUntilIdle()
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `old owner saved state cannot restore source or draft`() = runTest(dispatcher) {
        enable()
        select()
        val saved = restored()
        signIn("new-owner")
        val next = create(saved)
        next.initialize("persona", "Nova", "old-source")
        advanceUntilIdle()
        assertNull(next.uiState.value.draft)
        assertNull(next.uiState.value.selectedMessageId)
        assertTrue(repository.extractions.isEmpty())
    }

    @Test
    fun `late old session extraction failure cannot modify next session state`() = runTest(dispatcher) {
        enable()
        val gate = CompletableDeferred<Unit>()
        repository.onExtract = { withContext(NonCancellable) { gate.await() }; error("old failure") }
        vm.extractLocal()
        runCurrent()
        signIn("next-owner")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.operation)
        assertNull(vm.uiState.value.selectedMessageId)
    }

    @Test
    fun `late source lookup cannot display previous owner content`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        whenever(chat.getMessageById("source")).doSuspendableAnswer {
            withContext(NonCancellable) { gate.await() }
            message()
        }
        vm.showCandidateSource("candidate")
        runCurrent()
        signIn("next-owner")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.sources)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `same owner new session also clears source and editor`() = runTest(dispatcher) {
        signIn("owner")
        runCurrent()
        repository.rows.value = listOf(candidate())
        runCurrent()
        enable()
        select()
        signIn("owner")
        vm.confirm()
        assertNull(vm.uiState.value.draft)
        assertTrue(repository.confirmations.isEmpty())
    }

    @Test
    fun `revoking global switch also revokes independent extraction consent`() = runTest(dispatcher) {
        enable(cloud = true)
        vm.configure(false, true)
        advanceUntilIdle()
        assertEquals(MemoryPreferences(), vm.uiState.value.preferences)
        vm.extractCloud()
        vm.summarize()
        advanceUntilIdle()
        assertTrue(repository.extractions.isEmpty())
        assertEquals(0, repository.summaries)
    }

    @Test
    fun `list observations filter other owners personas and non pending tasks`() = runTest(dispatcher) {
        repository.rows.value = listOf(candidate(), candidate("foreign", "another"),
            candidate("wrong-persona").copy(personaId = "other"),
            candidate("running").copy(status = "RUNNING"), candidate("accepted").copy(status = "ACCEPTED"))
        runCurrent()
        assertEquals(listOf("candidate"), vm.uiState.value.candidates.map { it.id })
        enable()
        vm.selectCandidate("foreign")
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `repository observation failure has explicit reload recovery`() = runTest(dispatcher) {
        repository.loadFailure = true
        vm.reload()
        advanceUntilIdle()
        assertEquals(CurationError.LOAD, vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        repository.loadFailure = false
        vm.reload()
        advanceUntilIdle()
        assertEquals(listOf("candidate"), vm.uiState.value.candidates.map { it.id })
        vm.dismissError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `permanent candidate confirmation passes a null expiry`() = runTest(dispatcher) {
        enable()
        select()
        vm.updateDraft("Permanent", MemoryType.FACT, false, false, "")
        vm.chooseNew()
        vm.confirm()
        advanceUntilIdle()
        assertNull(repository.confirmations.single().expires)
    }

    @Test
    fun `late cancelled extraction cannot clear the next extraction operation`() = runTest(dispatcher) {
        enable()
        val oldGate = CompletableDeferred<Unit>()
        val nextGate = CompletableDeferred<Unit>()
        repository.onExtract = { withContext(NonCancellable) { oldGate.await() }; error("cancelled old extraction") }
        vm.extractLocal()
        runCurrent()
        vm.cancelExtraction()
        repository.onExtract = { nextGate.await() }
        vm.extractLocal()
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()
        assertEquals(CurationOperation.EXTRACTING, vm.uiState.value.operation)
        assertNull(vm.uiState.value.error)
        nextGate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.operation)
    }

    @Test
    fun `missing persona cannot operate`() = runTest(dispatcher) {
        val invalid = create()
        invalid.initialize(null, "Missing", "source")
        invalid.configure(true, true)
        invalid.proposeSelected()
        invalid.summarize()
        advanceUntilIdle()
        assertEquals(CurationError.INVALID_PERSONA, invalid.uiState.value.error)
        assertTrue(repository.extractions.isEmpty())
    }

    @Test
    fun `derived summaries remain separate from formal memory replacement choices`() {
        val summary = memory().copy(id = "summary-derived", type = MemoryType.FACT)
        val fact = memory().copy(id = "fact", type = MemoryType.FACT)
        val state = MemoryCurationUiState(
            draft = CurationDraft("candidate", "fact", MemoryType.FACT),
            memories = listOf(summary, fact)
        )
        assertEquals(listOf(summary), state.summaries)
        assertEquals(listOf(fact), state.possibleConflicts)
    }

    @Test fun `quality upgrade is independent disabled by default and consumed per request`() = runTest(dispatcher) {
        enable(cloud = true)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
        vm.allowQualityUpgrade(true)
        assertTrue(vm.uiState.value.qualityUpgradeAllowed)
        vm.extractLocal()
        runCurrent()
        assertEquals(listOf(true), repository.upgrades)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
        vm.extractLocal()
        runCurrent()
        assertEquals(listOf(true, false), repository.upgrades)
    }

    @Test fun `cloud consent and nonlocal source are both required for upgrade selection`() = runTest(dispatcher) {
        enable()
        vm.allowQualityUpgrade(true)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
        enable(cloud = true)
        whenever(chat.getMessageById("source")).thenReturn(message().copy(localOnly = true))
        vm.reload()
        runCurrent()
        vm.allowQualityUpgrade(true)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
    }

    @Test fun `reselecting source or revoking cloud consent clears upgrade permission`() = runTest(dispatcher) {
        enable(cloud = true)
        vm.allowQualityUpgrade(true)
        vm.selectMessage("source")
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
        vm.allowQualityUpgrade(true)
        enable(cloud = false)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
    }

    @Test fun `manual cloud extraction does not turn into another automatic attempt`() = runTest(dispatcher) {
        enable(cloud = true)
        vm.allowQualityUpgrade(true)
        vm.extractCloud()
        runCurrent()
        assertEquals(listOf(false), repository.upgrades)
        assertFalse(vm.uiState.value.qualityUpgradeAllowed)
    }

    private data class Confirmation(val id: String, val content: String, val type: MemoryType,
        val shared: Boolean, val cloud: Boolean, val expires: Long?, val replaceId: String? = null,
        val revision: Int? = null)

    private inner class FakeCuration : MemoryCurationRepository {
        val rows = MutableStateFlow<List<MemoryCandidate>>(emptyList())
        var settings = MemoryPreferences()
        var conflictRows = emptyList<Memory>()
        val extractions = mutableListOf<String>()
        val upgrades = mutableListOf<Boolean>()
        val confirmations = mutableListOf<Confirmation>()
        val ignored = mutableListOf<String>()
        var summaries = 0
        var summaryResult: Memory? = null
        var sourceIds = emptyList<String>()
        var sourceIdentity: AuthIdentity? = null
        var loadFailure = false
        var onExtract: suspend () -> Unit = {}
        var onConfirm: suspend () -> Memory = { memory() }
        override fun observe(personaId: String) = flow {
            if (loadFailure) error("Controlled observation failure")
            emitAll(rows)
        }
        override suspend fun preferences() = settings
        override suspend fun configure(enabled: Boolean, cloudExtractionAllowed: Boolean) {
            settings = MemoryPreferences(enabled, enabled && cloudExtractionAllowed)
        }
        override suspend fun propose(personaId: String, sourceMessageId: String, explicitOnly: Boolean) {
            assertFalse(explicitOnly)
            extractions += "selected:$personaId:$sourceMessageId"
            onExtract()
        }
        override suspend fun proposeWithModel(personaId: String, sourceMessageId: String, cloud: Boolean, allowQualityUpgrade: Boolean) {
            extractions += "${if (cloud) "cloud" else "local"}:$personaId:$sourceMessageId"
            upgrades += allowQualityUpgrade
            onExtract()
        }
        override suspend fun ignore(id: String) { ignored += id }
        override suspend fun conflicts(id: String) = conflictRows
        override suspend fun confirm(id: String, content: String, type: MemoryType, shared: Boolean,
            cloudAllowed: Boolean, expiresAt: Long?, replaceId: String?, expectedRevision: Int?): Memory {
            confirmations += Confirmation(id, content, type, shared, cloudAllowed, expiresAt, replaceId, expectedRevision)
            return onConfirm()
        }
        override suspend fun summarize(personaId: String): Memory? { summaries++; return summaryResult }
        override suspend fun summarySources(memoryId: String): List<String> {
            sourceIdentity = currentCoroutineContext()[AuthIdentity]
            return sourceIds
        }
    }
}
