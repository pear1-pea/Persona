package com.example.persona.features.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val chat: ChatRepository = mock()
    private val api: AuthApi = mock()
    private val auth = AuthManager(api, mock(), mock())
    private val handle = SavedStateHandle()
    private val instances = mutableListOf<MemoryViewModel>()
    private lateinit var vm: MemoryViewModel

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        Dispatchers.setMain(dispatcher)
        whenever(chat.getMessageById(any())).thenAnswer { call ->
            Message(call.getArgument(0), "persona", "Original", true)
        }
        vm = create(handle)
        vm.initialize("persona", "Nova")
    }

    @After
    fun tearDown() {
        instances.forEach { ViewModelStore().apply { put("memory", it); clear() } }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun create(state: SavedStateHandle = SavedStateHandle()) =
        MemoryViewModel(repository, auth, state, chat).also { instances.add(it) }

    private fun restored(state: SavedStateHandle) = SavedStateHandle(
        state.keys().associateWith { state.get<Any?>(it) }
    )

    private fun memory(id: String = "m", revision: Int = 3, owner: String = auth.identity.value.ownerId,
        persona: String? = "persona", source: String? = null) = Memory(
        id = id, ownerId = owner, personaId = persona, type = MemoryType.FACT,
        content = "Original fact", sourceMessageId = source, createdAt = 1, updatedAt = 2,
        expiresAt = 9999999999999, revision = revision
    )

    private fun content(value: String) {
        vm.updateDraft(vm.uiState.value.draft!!.copy(content = value))
    }

    private suspend fun signIn(owner: String) {
        whenever(api.login(any())).thenReturn(AuthSessionDto("token-$owner", "2099-01-01T00:00:00Z",
            AuthUserDto(owner, "$owner@test.dev")))
        auth.signIn("$owner@test.dev", "password")
    }

    @Test
    fun `manual new defaults to persona and local with no source`() = runTest(dispatcher) {
        vm.newMemory()
        assertEquals(MemoryDraft(), vm.uiState.value.draft)
        content("I prefer tea")
        vm.save()
        advanceUntilIdle()
        assertEquals(SaveRequest("persona", MemoryType.PREFERENCE, "I prefer tea", false),
            repository.saves.single())
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `sharing and cloud permission are independent opt ins`() = runTest(dispatcher) {
        vm.newMemory()
        vm.updateDraft(vm.uiState.value.draft!!.copy(content = "Tea", shared = true,
            cloudAllowed = true, type = MemoryType.GOAL))
        vm.save()
        advanceUntilIdle()
        val request = repository.saves.single()
        assertNull(request.persona)
        assertTrue(request.cloud)
        assertEquals(MemoryType.GOAL, request.type)
    }

    @Test
    fun `blank and overlong content never reach repository`() = runTest(dispatcher) {
        vm.newMemory()
        listOf("", " \n", "x".repeat(401)).forEach {
            content(it)
            vm.save()
            assertEquals(MemoryError.INVALID_CONTENT, vm.uiState.value.error)
            assertEquals(it, vm.uiState.value.draft!!.content)
        }
        advanceUntilIdle()
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `boundary lengths save unchanged`() = runTest(dispatcher) {
        listOf("x", " x" + "y".repeat(397) + " ").forEach {
            vm.newMemory()
            content(it)
            vm.save()
            advanceUntilIdle()
        }
        assertEquals(listOf(1, 400), repository.saves.map { it.content.length })
        assertTrue(repository.saves.last().content.endsWith(" "))
    }

    @Test
    fun `source prefill is complete and must be revised before saving`() = runTest(dispatcher) {
        vm = create(SavedStateHandle())
        val original = "source".repeat(100)
        whenever(chat.getMessageById("user-message")).thenReturn(
            Message("user-message", "persona", original, true))
        vm.initialize("persona", "Nova", "user-message")
        advanceUntilIdle()
        assertEquals(original, vm.uiState.value.draft!!.content)
        vm.save()
        assertTrue(repository.saves.isEmpty())
        content("Short user-approved fact")
        whenever(chat.getMessageById("user-message")).thenReturn(
            Message("user-message", "persona", original, true))
        vm.showSource("user-message")
        advanceUntilIdle()
        assertEquals(original, vm.uiState.value.source!!.content)
        vm.closeSource()
        vm.save()
        advanceUntilIdle()
        assertEquals("user-message", repository.saves.single().source)
        assertEquals("Short user-approved fact", repository.saves.single().content)
        verify(chat, org.mockito.kotlin.times(2)).getMessageById("user-message")
    }

    @Test
    fun `editing captures revision and preserves provenance expiry`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory(source = "source"))
        runCurrent()
        vm.editMemory("m")
        repository.rows.value = listOf(memory(revision = 4, source = "source"))
        runCurrent()
        vm.updateDraft(vm.uiState.value.draft!!.copy(content = "Changed", id = "forged",
            revision = 99, sourceMessageId = "forged", expiresAt = null))
        vm.save()
        advanceUntilIdle()
        val saved = repository.saves.single()
        assertEquals("m", saved.id)
        assertEquals(3, saved.revision)
        assertEquals("source", saved.source)
        assertEquals(9999999999999, saved.expires)
    }

    @Test
    fun `CAS failure keeps original revision and draft until explicit cancellation`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.editMemory("m")
        content("Unsaved edit")
        repository.onSave = { throw IllegalStateException("revision changed") }
        vm.save()
        advanceUntilIdle()
        assertEquals(MemoryError.SAVE_FAILED, vm.uiState.value.error)
        assertEquals(3, vm.uiState.value.draft!!.revision)
        assertEquals("Unsaved edit", vm.uiState.value.draft!!.content)
        assertNull(vm.uiState.value.operation)
        vm.acknowledgeError()
        assertNull(vm.uiState.value.error)
        vm.cancelDraft()
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `save blocks rapid repeats edit delete new and cancel`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.newMemory()
        content("Saved once")
        val gate = CompletableDeferred<Unit>()
        repository.onSave = { gate.await(); memory() }
        vm.save()
        vm.save()
        vm.cancelDraft()
        vm.newMemory()
        vm.editMemory("m")
        vm.requestDelete("m")
        content("Must not change")
        runCurrent()
        assertEquals(1, repository.saves.size)
        assertEquals("Saved once", vm.uiState.value.draft!!.content)
        assertEquals(MemoryOperation.SAVING, vm.uiState.value.operation)
        assertNull(vm.uiState.value.pendingDelete)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `delete requires confirmation and uses confirmation revision`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.confirmDelete(memory())
        assertTrue(repository.deletes.isEmpty())
        vm.requestDelete("m")
        val target = vm.uiState.value.pendingDelete!!
        repository.rows.value = listOf(memory(revision = 4))
        runCurrent()
        vm.confirmDelete(target)
        vm.confirmDelete(target)
        advanceUntilIdle()
        assertEquals(listOf("m" to 3), repository.deletes)
        assertNull(vm.uiState.value.pendingDelete)
    }

    @Test
    fun `cancelling confirmation never deletes`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.requestDelete("m")
        val target = vm.uiState.value.pendingDelete!!
        vm.cancelDelete()
        vm.confirmDelete(target)
        advanceUntilIdle()
        assertTrue(repository.deletes.isEmpty())
    }

    @Test
    fun `delete failure remains visible without UI collector`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory())
        repository.onDelete = { throw IllegalStateException("revision changed") }
        runCurrent()
        vm.requestDelete("m")
        vm.confirmDelete(vm.uiState.value.pendingDelete!!)
        advanceUntilIdle()
        assertEquals(MemoryError.DELETE_FAILED, vm.uiState.value.error)
        assertNotNull(vm.uiState.value.pendingDelete)
        assertNull(vm.uiState.value.operation)
    }

    @Test
    fun `repository cancellation preserves draft and emits no failure`() = runTest(dispatcher) {
        repository.onSave = { throw CancellationException() }
        vm.newMemory()
        content("Keep draft")
        vm.save()
        advanceUntilIdle()
        assertNull(vm.uiState.value.operation)
        assertNull(vm.uiState.value.error)
        assertEquals("Keep draft", vm.uiState.value.draft!!.content)
    }

    @Test
    fun `restoration keeps new draft type scope consent and source identity`() = runTest(dispatcher) {
        val sourceHandle = SavedStateHandle()
        vm = create(sourceHandle)
        vm.initialize("persona", "Nova", "source")
        vm.updateDraft(vm.uiState.value.draft!!.copy(content = "Approved", shared = true,
            cloudAllowed = true, type = MemoryType.RELATIONSHIP))
        val next = create(restored(sourceHandle))
        next.initialize("persona", "Nova", "other-source")
        assertEquals(vm.uiState.value.draft, next.uiState.value.draft)
        assertEquals("source", next.uiState.value.draft!!.sourceMessageId)
        assertNull(next.uiState.value.operation)
    }

    @Test
    fun `restoration keeps edit id revision and expiry`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory(source = "source"))
        runCurrent()
        vm.editMemory("m")
        content("Editing after recreation")
        val next = create(restored(handle))
        assertEquals(vm.uiState.value.draft, next.uiState.value.draft)
        next.save()
        advanceUntilIdle()
        assertEquals(3, repository.saves.single().revision)
    }

    @Test
    fun `cancelled source draft is not repopulated on recreation`() = runTest(dispatcher) {
        val sourceHandle = SavedStateHandle()
        vm = create(sourceHandle)
        vm.initialize("persona", "Nova", "source")
        vm.cancelDraft()
        val next = create(restored(sourceHandle))
        next.initialize("persona", "Nova", "source")
        assertNull(next.uiState.value.draft)
    }

    @Test
    fun `identity change synchronously blocks stale save and clears draft list confirmation`() = runTest(dispatcher) {
        signIn("old-owner")
        runCurrent()
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.newMemory()
        content("Old owner private draft")
        auth.logout()
        // Do not dispatch the identity collector before attempting the action.
        vm.save()
        assertNull(vm.uiState.value.draft)
        assertTrue(vm.uiState.value.memories.isEmpty())
        assertTrue(repository.saves.isEmpty())
        runCurrent()
        assertTrue(vm.uiState.value.memories.isEmpty())
    }

    @Test
    fun `new session for same owner clears all private UI state`() = runTest(dispatcher) {
        signIn("owner")
        runCurrent()
        repository.rows.value = listOf(memory())
        runCurrent()
        vm.requestDelete("m")
        val target = vm.uiState.value.pendingDelete!!
        signIn("owner")
        vm.confirmDelete(target)
        assertNull(vm.uiState.value.pendingDelete)
        assertTrue(vm.uiState.value.memories.isEmpty())
        advanceUntilIdle()
        assertTrue(repository.deletes.isEmpty())
    }

    @Test
    fun `saved old owner draft never restores or reimports source for new owner`() = runTest(dispatcher) {
        vm.newMemory()
        content("Private offline draft")
        val oldState = restored(handle)
        signIn("another-owner")
        val next = create(oldState)
        next.initialize("persona", "Nova", "old-source")
        assertNull(next.uiState.value.draft)
        next.save()
        advanceUntilIdle()
        assertTrue(repository.saves.isEmpty())
    }

    @Test
    fun `late cancelled save cannot clear next identity draft or surface its error`() = runTest(dispatcher) {
        vm.newMemory()
        content("Old secret")
        val gate = CompletableDeferred<Unit>()
        val originalIdentity = auth.identity.value
        var captured: AuthIdentity? = null
        repository.onSave = {
            captured = currentCoroutineContext()[AuthIdentity]
            withContext(NonCancellable) { gate.await() }
            throw IllegalStateException("Late old session failure")
        }
        vm.save()
        runCurrent()
        signIn("new-owner")
        runCurrent()
        vm.newMemory()
        content("New owner's draft")
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(originalIdentity, captured)
        assertEquals("New owner's draft", vm.uiState.value.draft!!.content)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.operation)
    }

    @Test
    fun `observation failure persists and explicit retry recovers list`() = runTest(dispatcher) {
        repository.loadFailure = true
        vm.retryLoading()
        runCurrent()
        assertEquals(MemoryError.LOAD_FAILED, vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        repository.loadFailure = false
        repository.rows.value = listOf(memory())
        vm.retryLoading()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.memories.size)
        assertEquals(MemoryError.LOAD_FAILED, vm.uiState.value.error)
        vm.acknowledgeError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `list excludes other owners other personas and tombstones`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory(), memory("shared", persona = null),
            memory("other", owner = "other-owner"), memory("different", persona = "different"),
            memory("deleted").copy(deleted = true))
        runCurrent()
        assertEquals(listOf("m", "shared"), vm.uiState.value.memories.map { it.id })
        vm.editMemory("other")
        assertNull(vm.uiState.value.draft)
    }

    @Test
    fun `source lookup reads old shared sources by ID and rejects unstable messages`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory(persona = null, source = "old-source"))
        runCurrent()
        whenever(chat.getMessageById("old-source")).thenReturn(
            Message("old-source", "original-persona", "Old user context", true, timestamp = 1L))
        vm.showSource("old-source")
        assertTrue(vm.uiState.value.source!!.loading)
        advanceUntilIdle()
        assertEquals("Old user context", vm.uiState.value.source!!.content)
        verify(chat).getMessageById("old-source")
        listOf(Message("old-source", "persona", "Assistant", false),
            Message("old-source", "persona", "Failed", true, status = MessageStatus.FAILED),
            Message("wrong-id", "persona", "Wrong source", true)).forEach { invalid ->
            whenever(chat.getMessageById("old-source")).thenReturn(invalid)
            vm.showSource("old-source")
            advanceUntilIdle()
            assertNull(vm.uiState.value.source!!.content)
        }
    }

    @Test
    fun `source prefill and lookup reject late results after identity changes`() = runTest(dispatcher) {
        val next = create()
        next.initialize("persona", "Nova", "source")
        val gate = CompletableDeferred<Unit>()
        whenever(chat.getMessageById("source")).doSuspendableAnswer {
            withContext(NonCancellable) { gate.await() }
            Message("source", "persona", "Old owner text", true)
        }
        next.showSource("source")
        runCurrent()
        signIn("new-owner")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(next.uiState.value.source)
        assertNull(next.uiState.value.draft)
    }

    @Test
    fun `closing a pending source lookup cancels and ignores its result`() = runTest(dispatcher) {
        repository.rows.value = listOf(memory(source = "source"))
        runCurrent()
        vm.showSource("source")
        vm.closeSource()
        advanceUntilIdle()
        assertNull(vm.uiState.value.source)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `late source prefill does not overwrite a user edited draft`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        whenever(chat.getMessageById("source")).doSuspendableAnswer {
            gate.await()
            Message("source", "persona", "Original source", true)
        }
        val next = create()
        next.initialize("persona", "Nova", "source")
        runCurrent()
        next.updateDraft(next.uiState.value.draft!!.copy(content = "My explicit correction"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("My explicit correction", next.uiState.value.draft!!.content)
    }

    @Test
    fun `missing persona cannot create or persist memories`() = runTest(dispatcher) {
        val invalid = create()
        invalid.initialize(null, "Missing")
        invalid.newMemory()
        invalid.save()
        advanceUntilIdle()
        assertEquals(MemoryError.INVALID_PERSONA, invalid.uiState.value.error)
        assertNull(invalid.uiState.value.draft)
        assertTrue(repository.saves.isEmpty())
    }

    private data class SaveRequest(
        val persona: String?, val type: MemoryType, val content: String, val cloud: Boolean,
        val source: String? = null, val id: String? = null, val revision: Int? = null,
        val expires: Long? = null
    )

    private inner class FakeRepository : MemoryRepository {
        val rows = MutableStateFlow<List<Memory>>(emptyList())
        override val changes = MutableStateFlow(0L)
        val saves = mutableListOf<SaveRequest>()
        val deletes = mutableListOf<Pair<String, Int>>()
        var loadFailure = false
        var onSave: suspend (SaveRequest) -> Memory = { memory() }
        var onDelete: suspend () -> Unit = {}

        override fun observe(personaId: String): Flow<List<Memory>> = flow {
            if (loadFailure) error("Local read failed")
            emitAll(rows)
        }

        override suspend fun save(personaId: String?, type: MemoryType, content: String,
            cloudAllowed: Boolean, sourceMessageId: String?, id: String?, expectedRevision: Int?,
            expiresAt: Long?): Memory {
            val request = SaveRequest(personaId, type, content, cloudAllowed, sourceMessageId,
                id, expectedRevision, expiresAt)
            saves += request
            return onSave(request)
        }

        override suspend fun delete(id: String, expectedRevision: Int) {
            deletes += id to expectedRevision
            onDelete()
        }

        override suspend fun deleteForPersona(personaId: String) = Unit
        override suspend fun selectForPrompt(personaId: String, cloud: Boolean): List<Memory> = emptyList()
    }
}
