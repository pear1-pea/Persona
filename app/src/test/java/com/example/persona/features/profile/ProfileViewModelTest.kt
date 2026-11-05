package com.example.persona.features.profile

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
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository: PersonaRepository = mock()
    private val authApi: AuthApi = mock()
    private val authManager = AuthManager(authApi, mock<SessionTokenStore>(), mock<PersonaDao>())
    private lateinit var viewModel: ProfileViewModel
    private val original = Persona("1", "Nova", "", "", listOf("Curious"), "Original story", "a")

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-a", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev"))
        )
        authManager.signIn("a@test.dev", "password")
        whenever(repository.getMyPersonas()).thenReturn(listOf(original))
        viewModel = ProfileViewModel(repository, authManager)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load and refresh reflect changed persona data`() = runTest {
        assertEquals(listOf(original), viewModel.myPersonas.value)
        val edited = original.copy(backstory = "Actual edited backstory")
        val created = original.copy(id = "2", name = "Created persona")
        whenever(repository.getMyPersonas()).thenReturn(listOf(edited, created))
        viewModel.loadMyPersonas()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(edited, created), viewModel.myPersonas.value)
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
        whenever(repository.getMyPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else listOf(latest)
        }
        viewModel.loadMyPersonas()
        dispatcher.scheduler.runCurrent()
        viewModel.loadMyPersonas()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(latest), viewModel.myPersonas.value)
        oldResponse.complete(listOf(original))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(latest), viewModel.myPersonas.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `account switch clears old data before new load and ignores late old response`() = runTest {
        val oldResponse = CompletableDeferred<List<Persona>>()
        val newResponse = CompletableDeferred<List<Persona>>()
        var calls = 0
        whenever(repository.getMyPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
        }
        viewModel.loadMyPersonas()
        dispatcher.scheduler.runCurrent()
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-b", "2099-01-01T00:00:00Z", AuthUserDto("b", "b@test.dev"))
        )
        authManager.signIn("b@test.dev", "password")
        dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Persona>(), viewModel.myPersonas.value)

        val nextAccountPersona = original.copy(id = "b-1", creatorId = "b")
        newResponse.complete(listOf(nextAccountPersona))
        dispatcher.scheduler.runCurrent()
        oldResponse.complete(listOf(original))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(nextAccountPersona), viewModel.myPersonas.value)
    }

    @Test
    fun `new session for same owner clears list while loading`() = runTest {
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("first-token", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev")),
            AuthSessionDto("second-token", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev"))
        )
        authManager.signIn("a@test.dev", "password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(original), viewModel.myPersonas.value)
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getMyPersonas()).doSuspendableAnswer { response.await() }
        authManager.signIn("a@test.dev", "password")
        dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Persona>(), viewModel.myPersonas.value)
        response.complete(emptyList())
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `counts use all own personas and reflect visibility edits and deletion`() = runTest {
        val privatePersona = original.copy(id = "2", isPublic = false)
        whenever(repository.getMyPersonas()).thenReturn(listOf(original, privatePersona))
        viewModel.loadMyPersonas()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ProfileCounts(2, 1, 1), viewModel.counts)

        whenever(repository.getMyPersonas()).thenReturn(listOf(original.copy(isPublic = false)))
        viewModel.loadMyPersonas()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ProfileCounts(1, 0, 1), viewModel.counts)

        whenever(repository.getMyPersonas()).thenReturn(emptyList())
        viewModel.loadMyPersonas()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ProfileCounts(0, 0, 0), viewModel.counts)
    }

    @Test
    fun `logout clears own counts before offline load completes`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getMyPersonas()).doSuspendableAnswer { response.await() }
        authManager.logout()
        dispatcher.scheduler.runCurrent()
        assertEquals(ProfileCounts(0, 0, 0), viewModel.counts)
        response.complete(emptyList())
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `refresh after logout clears previous own counts synchronously`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getMyPersonas()).doSuspendableAnswer { response.await() }
        authManager.logout()
        viewModel.loadMyPersonas()
        assertEquals(emptyList<Persona>(), viewModel.myPersonas.value)
        assertEquals(ProfileCounts(0, 0, 0), viewModel.counts)
        dispatcher.scheduler.runCurrent()
        val offlinePrivate = original.copy(creatorId = "OFFLINE_USER", isPublic = false)
        response.complete(listOf(offlinePrivate))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ProfileCounts(1, 0, 1), viewModel.counts)
    }

    @Test
    fun `failed refresh preserves own list and counts and reports the error`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        whenever(repository.getMyPersonas()).doSuspendableAnswer { error("Load failed") }
        viewModel.loadMyPersonas()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(original), viewModel.myPersonas.value)
        assertEquals(ProfileCounts(1, 1, 0), viewModel.counts)
        assertEquals(listOf("Load failed"), errors)
    }
}
