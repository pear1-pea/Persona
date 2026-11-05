package com.example.persona.features.creation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.data.repository.CloudChatRepository
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CreatePersonaViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository: PersonaRepository = mock()
    private val cloud: CloudChatRepository = mock()
    private val authApi: AuthApi = mock()
    private val auth = AuthManager(authApi, mock(), mock())
    private val savedState = SavedStateHandle()
    private val instances = mutableListOf<CreatePersonaViewModel>()
    private lateinit var viewModel: CreatePersonaViewModel
    private val validJson = """{"name":"Nova","backstory":"A space explorer.","traits":["Kind"]}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = createViewModel(savedState)
    }

    @After
    fun tearDown() {
        instances.forEach { ViewModelStore().apply { put("creation", it); clear() } }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()): CreatePersonaViewModel =
        CreatePersonaViewModel(repository, cloud, auth, handle).also { instances.add(it) }

    private suspend fun signIn(owner: String, token: String = "token-$owner") {
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto(token, "2099-01-01T00:00:00Z", AuthUserDto(owner, "$owner@test.dev"))
        )
        auth.signIn("$owner@test.dev", "password")
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `invalid draft never reaches persistence`() = runTest(dispatcher) {
        viewModel.savePersona(null, "Nova", " ", listOf("Kind"), true)
        advanceUntilIdle()
        viewModel.savePersona(null, "x".repeat(121), "Story", listOf("Kind"), true)
        advanceUntilIdle()
        viewModel.savePersona(null, "Nova", "x".repeat(12001), listOf("Kind"), true)
        advanceUntilIdle()
        viewModel.savePersona(null, "Nova", "Story", listOf("x".repeat(81)), true)
        advanceUntilIdle()
        viewModel.savePersona("edit-id", "Nova", "Story", List(21) { "Kind" }, true)
        advanceUntilIdle()
        verifyNoInteractions(repository)
    }

    @Test
    fun `invalid AI result gets exactly one repair attempt`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).thenReturn("null", validJson)
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud, times(2)).generatePersonaProfile(any())
        verifyNoInteractions(repository)
        assertEquals("Nova", viewModel.uiState.value.draft?.name)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `two invalid results exhaust repair budget`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).thenReturn("null")
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud, times(2)).generatePersonaProfile(any())
        verifyNoInteractions(repository)
        assertEquals(CreationError.INVALID_PROFILE, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.draft)
    }

    @Test
    fun `rapid generation clicks start one request`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).thenReturn(validJson)
        viewModel.generateAI("space")
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
    }

    @Test
    fun `rapid save clicks persist once`() = runTest(dispatcher) {
        repeat(2) {
            viewModel.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        }
        advanceUntilIdle()
        verify(repository).addPersona("Nova", listOf("Kind"), "Story", true)
        assertTrue(viewModel.uiState.value.completed)
        viewModel.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        advanceUntilIdle()
        verify(repository).addPersona("Nova", listOf("Kind"), "Story", true)
    }

    @Test
    fun `generation retains loading and result without a UI subscriber`() = runTest(dispatcher) {
        val response = CompletableDeferred<String>()
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer { response.await() }
        viewModel.initializeDraft(CreationDraft(editingId = "edit", isPublic = false))
        viewModel.generateAI("space")
        runCurrent()
        assertEquals(CreationOperation.GENERATING, viewModel.uiState.value.operation)
        viewModel.savePersona(null, "Name", "Story", listOf("Kind"), true)
        viewModel.deletePersona("edit")
        verifyNoInteractions(repository)
        response.complete(validJson)
        advanceUntilIdle()
        val draft = viewModel.uiState.value.draft!!
        assertEquals("Nova", draft.name)
        assertEquals("edit", draft.editingId)
        assertFalse(draft.isPublic)
        assertEquals(listOf("Kind"), draft.traits)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `invalid repaired result preserves existing draft`() = runTest(dispatcher) {
        val original = CreationDraft(name = "Draft", story = "My story", traits = listOf("Kind"))
        viewModel.initializeDraft(original)
        whenever(cloud.generatePersonaProfile(any())).thenReturn("null")
        viewModel.generateAI("space")
        advanceUntilIdle()
        assertEquals(original, viewModel.uiState.value.draft)
        assertEquals(CreationError.INVALID_PROFILE, viewModel.uiState.value.error)
    }

    @Test
    fun `transport failure is not retried and releases operation`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).thenThrow(IllegalStateException("network"))
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        assertEquals(CreationError.GENERATION_FAILED, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `cancellation is not repaired or exposed as an error`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer { throw CancellationException() }
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `total generation deadline cancels a stalled request`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer { awaitCancellation() }
        viewModel.generateAI("space")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        assertEquals(CreationError.GENERATION_TIMEOUT, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `repair request is bounded and never includes malformed output`() = runTest(dispatcher) {
        whenever(cloud.generatePersonaProfile(any())).thenReturn("private-invalid-response", validJson)
        viewModel.generateAI("x".repeat(20000))
        advanceUntilIdle()
        val requests = argumentCaptor<String>()
        verify(cloud, times(2)).generatePersonaProfile(requests.capture())
        assertTrue(requests.allValues.all { it.length < 3000 })
        assertFalse(requests.lastValue.contains("private-invalid-response"))
    }

    @Test
    fun `both generation attempts use the original coroutine identity`() = runTest(dispatcher) {
        signIn("a")
        val expected = auth.identity.value
        val requestIdentities = mutableListOf<AuthIdentity?>()
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer {
            requestIdentities.add(currentCoroutineContext()[AuthIdentity])
            if (requestIdentities.size == 1) "null" else validJson
        }
        viewModel.generateAI("space")
        advanceUntilIdle()
        assertEquals(2, requestIdentities.size)
        requestIdentities.forEach { assertSame(expected, it) }
        assertEquals("Nova", viewModel.uiState.value.draft?.name)
    }

    @Test
    fun `account switch before repair never calls the new account`() = runTest(dispatcher) {
        signIn("a")
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer {
            signIn("b")
            "null"
        }
        viewModel.generateAI("private theme")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        verifyNoInteractions(repository)
        assertEquals(CreationDraft(), viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.operation)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `late old account result cannot clear a new generation or overwrite its draft`() = runTest(dispatcher) {
        signIn("a")
        val oldResponse = CompletableDeferred<String>()
        val newResponse = CompletableDeferred<String>()
        var calls = 0
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
        }
        viewModel.generateAI("old theme")
        runCurrent()
        signIn("b")
        val draft = CreationDraft(name = "New draft", story = "New story", traits = listOf("Kind"))
        viewModel.updateDraft(draft)
        viewModel.generateAI("new theme")
        runCurrent()
        oldResponse.complete(validJson)
        runCurrent()
        assertEquals(draft, viewModel.uiState.value.draft)
        assertEquals(CreationOperation.GENERATING, viewModel.uiState.value.operation)
        assertNull(viewModel.uiState.value.error)
        newResponse.complete(validJson.replace("Nova", "New Nova"))
        advanceUntilIdle()
        assertEquals("New Nova", viewModel.uiState.value.draft?.name)
        assertEquals(2, calls)
    }

    @Test
    fun `same account new session cancels generation and clears its private draft`() = runTest(dispatcher) {
        signIn("a")
        viewModel.updateDraft(CreationDraft(name = "Private draft"))
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer { awaitCancellation() }
        viewModel.generateAI("theme")
        runCurrent()
        signIn("a", "replacement-token")
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        assertEquals(CreationDraft(), viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `repair shares the first attempts deadline`() = runTest(dispatcher) {
        var calls = 0
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer {
            if (calls++ == 0) {
                kotlinx.coroutines.delay(59000)
                "null"
            } else awaitCancellation()
        }
        viewModel.generateAI("space")
        advanceTimeBy(59000)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(CreationOperation.GENERATING, viewModel.uiState.value.operation)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(CreationError.GENERATION_TIMEOUT, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun `restored draft retains edits generated chips and visibility instead of intent defaults`() = runTest(dispatcher) {
        val draft = CreationDraft("edit", "Edited", "Draft story", listOf("Kind", "Custom"), listOf("Custom"), false)
        viewModel.initializeDraft(draft)
        viewModel.initializeDraft(CreationDraft(name = "Stale intent"))
        assertEquals(draft, viewModel.uiState.value.draft)
        val restored = createViewModel(SavedStateHandle(savedState.keys().associateWith {
            savedState.get<Any?>(it)
        }))
        restored.initializeDraft(CreationDraft(name = "Stale intent"))
        assertEquals(draft, restored.uiState.value.draft)
    }

    @Test
    fun `saved state from another owner cannot restore private content or completion`() = runTest(dispatcher) {
        signIn("a")
        viewModel.updateDraft(CreationDraft("private-id", "Private", "Private story", traits = listOf("Kind")))
        viewModel.savePersona(null, "Private", "Story", listOf("Kind"), false)
        advanceUntilIdle()
        val snapshot = savedState.keys().associateWith { savedState.get<Any?>(it) }
        signIn("b")
        val restored = createViewModel(SavedStateHandle(snapshot))
        assertEquals(CreationDraft(), restored.uiState.value.draft)
        assertFalse(restored.uiState.value.completed)
    }

    @Test
    fun `completed save remains completed after restoration and cannot save twice`() = runTest(dispatcher) {
        viewModel.initializeDraft(CreationDraft(name = "Nova", story = "Story", traits = listOf("Kind")))
        viewModel.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        advanceUntilIdle()
        val restored = createViewModel(SavedStateHandle(savedState.keys().associateWith {
            savedState.get<Any?>(it)
        }))
        assertTrue(restored.uiState.value.completed)
        restored.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        restored.generateAI("space")
        advanceUntilIdle()
        verify(repository).addPersona("Nova", listOf("Kind"), "Story", true)
        verifyNoInteractions(cloud)
    }

    @Test
    fun `save blocks edits generation deletion and duplicate saves until it completes`() = runTest(dispatcher) {
        val response = CompletableDeferred<Unit>()
        val draft = CreationDraft("edit", "Nova", "Story", listOf("Kind"), listOf("Kind"), false)
        viewModel.initializeDraft(draft)
        whenever(repository.updatePersona("edit", "Nova", listOf("Kind"), "Story", false))
            .doSuspendableAnswer { response.await() }
        viewModel.savePersona("edit", " Nova ", " Story ", listOf(" Kind "), false)
        runCurrent()
        assertEquals(CreationOperation.SAVING, viewModel.uiState.value.operation)
        viewModel.savePersona("edit", "Nova", "Story", listOf("Kind"), false)
        viewModel.updateDraft(CreationDraft(name = "Overwritten"))
        viewModel.generateAI("space")
        viewModel.deletePersona("edit")
        assertEquals(draft, viewModel.uiState.value.draft)
        response.complete(Unit)
        advanceUntilIdle()
        verify(repository).updatePersona("edit", "Nova", listOf("Kind"), "Story", false)
        verify(repository, times(0)).deletePersona(any())
        verifyNoInteractions(cloud)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `save failure preserves draft and permits an explicit retry`() = runTest(dispatcher) {
        val draft = CreationDraft(name = "Nova", story = "Story", traits = listOf("Kind"))
        viewModel.initializeDraft(draft)
        whenever(repository.addPersona("Nova", listOf("Kind"), "Story", true))
            .thenThrow(IllegalStateException("network")).thenReturn(Unit)
        viewModel.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        advanceUntilIdle()
        assertEquals(CreationError.SAVE_FAILED, viewModel.uiState.value.error)
        assertEquals(draft, viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.operation)
        assertFalse(viewModel.uiState.value.completed)
        viewModel.savePersona(null, "Nova", "Story", listOf("Kind"), true)
        advanceUntilIdle()
        verify(repository, times(2)).addPersona("Nova", listOf("Kind"), "Story", true)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `delete clicks are serialized and completion is retained`() = runTest(dispatcher) {
        val response = CompletableDeferred<Unit>()
        whenever(repository.deletePersona("edit")).doSuspendableAnswer { response.await() }
        viewModel.deletePersona("edit")
        viewModel.deletePersona("edit")
        runCurrent()
        assertEquals(CreationOperation.DELETING, viewModel.uiState.value.operation)
        response.complete(Unit)
        advanceUntilIdle()
        viewModel.deletePersona("edit")
        advanceUntilIdle()
        verify(repository).deletePersona("edit")
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `late save from an old account cannot complete or unlock a new accounts save`() = runTest(dispatcher) {
        signIn("a")
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        val owners = mutableListOf<String?>()
        whenever(repository.addPersona(any(), any(), any(), any())).doSuspendableAnswer {
            owners.add(currentCoroutineContext()[AuthIdentity]?.ownerId)
            if (owners.size == 1) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
        }
        viewModel.savePersona(null, "Old", "Story", listOf("Kind"), true)
        runCurrent()
        signIn("b")
        viewModel.savePersona(null, "New", "Story", listOf("Kind"), true)
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertEquals(CreationOperation.SAVING, viewModel.uiState.value.operation)
        assertFalse(viewModel.uiState.value.completed)
        assertNull(viewModel.uiState.value.error)
        newResponse.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), owners)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `clearing the viewmodel cancels generation without applying its late result`() = runTest(dispatcher) {
        val original = CreationDraft(name = "Draft", story = "My story", traits = listOf("Kind"))
        viewModel.initializeDraft(original)
        val response = CompletableDeferred<String>()
        whenever(cloud.generatePersonaProfile(any())).doSuspendableAnswer {
            withContext(NonCancellable) { response.await() }
        }
        viewModel.generateAI("space")
        runCurrent()
        ViewModelStore().apply { put("creation", viewModel); clear() }
        response.complete(validJson)
        advanceUntilIdle()
        verify(cloud).generatePersonaProfile(any())
        verifyNoInteractions(repository)
        assertEquals(original, viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.operation)
    }
}
