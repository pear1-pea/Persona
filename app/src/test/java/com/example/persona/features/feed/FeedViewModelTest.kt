package com.example.persona.features.feed

import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository: PersonaRepository = mock()
    private val authApi: AuthApi = mock()
    private val authManager = AuthManager(authApi, mock<SessionTokenStore>(), mock<PersonaDao>())
    private lateinit var viewModel: FeedViewModel
    private val original = Persona("1", "Nova", "", "", listOf("Curious"), "Original story", "a")

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-a", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev"))
        )
        authManager.signIn("a@test.dev", "password")
        whenever(repository.getPersonas()).thenReturn(listOf(original))
        viewModel = FeedViewModel(repository, authManager)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load and refresh reflect changed persona data`() = runTest {
        assertEquals(listOf(original), viewModel.uiState.value)
        val edited = original.copy(backstory = "Actual edited backstory")
        val created = original.copy(id = "2", name = "Created persona")
        whenever(repository.getPersonas()).thenReturn(listOf(edited, created))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(edited, created), viewModel.uiState.value)
    }

    @Test
    fun `late refresh cannot overwrite newer refresh or emit cancellation error`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        val oldResponse = CompletableDeferred<List<Persona>>()
        val latest = original.copy(backstory = "Latest")
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else listOf(latest)
        }
        viewModel.refresh()
        dispatcher.scheduler.runCurrent()
        viewModel.refresh()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(latest), viewModel.uiState.value)
        oldResponse.complete(listOf(original))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(latest), viewModel.uiState.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `account switch clears old data before new load and ignores late old response`() = runTest {
        val oldResponse = CompletableDeferred<List<Persona>>()
        val newResponse = CompletableDeferred<List<Persona>>()
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
        }
        viewModel.refresh()
        dispatcher.scheduler.runCurrent()
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-b", "2099-01-01T00:00:00Z", AuthUserDto("b", "b@test.dev"))
        )
        authManager.signIn("b@test.dev", "password")
        dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)

        val nextAccountPersona = original.copy(id = "b-1", creatorId = "b")
        newResponse.complete(listOf(nextAccountPersona))
        dispatcher.scheduler.runCurrent()
        oldResponse.complete(listOf(original))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(nextAccountPersona), viewModel.uiState.value)
    }

    @Test
    fun `new session for same owner clears list while loading`() = runTest {
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("first-token", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev")),
            AuthSessionDto("second-token", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev"))
        )
        authManager.signIn("a@test.dev", "password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(original), viewModel.uiState.value)
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getPersonas()).doSuspendableAnswer { response.await() }
        authManager.signIn("a@test.dev", "password")
        dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        response.complete(emptyList())
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `refresh after logout clears the previous account synchronously`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getPersonas()).doSuspendableAnswer { response.await() }
        authManager.logout()
        viewModel.refresh()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        dispatcher.scheduler.runCurrent()
        val offlinePersona = original.copy(id = "offline", creatorId = "OFFLINE_USER")
        response.complete(listOf(offlinePersona))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(offlinePersona), viewModel.uiState.value)
    }

    @Test
    fun `superseded request failure cannot emit an error or replace the latest list`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        val response = CompletableDeferred<Unit>()
        val latest = original.copy(backstory = "Latest")
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) {
                response.await()
                error("Obsolete request failed")
            } else listOf(latest)
        }
        viewModel.refresh()
        dispatcher.scheduler.runCurrent()
        viewModel.refresh()
        dispatcher.scheduler.runCurrent()
        response.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(latest), viewModel.uiState.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `refresh failure keeps current data and reports the error`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        whenever(repository.getPersonas()).doSuspendableAnswer { error("Load failed") }
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(original), viewModel.uiState.value)
        assertEquals(listOf("Load failed"), errors)
    }
}
