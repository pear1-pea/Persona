package com.example.persona.features.discovery

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class DiscoveryViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val repository: PersonaRepository = mock()
    private val authApi: AuthApi = mock()
    private val authManager = AuthManager(authApi, mock<SessionTokenStore>(), mock<PersonaDao>())
    private lateinit var viewModel: DiscoveryViewModel

    private val personas = listOf(
        Persona("1", "Aetheris", "", "", listOf("Sci-Fi", "Smart"), "Quantum explorer", "system"),
        Persona("2", "Nova", "", "", listOf("Space", "Curious"), "Cosmic wanderer from Space", "system"),
        Persona("3", "Glitch", "", "", listOf("Cyberpunk", "Dark"), "Digital ghost", "system"),
        Persona("4", "Sakura", "", "", listOf("Anime", "Cute"), "Magical schoolgirl", "system"),
    )

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-a", "2099-01-01T00:00:00Z", AuthUserDto("a", "a@test.dev"))
        )
        authManager.signIn("a@test.dev", "password")
        whenever(repository.getPersonas()).thenReturn(personas)
        viewModel = DiscoveryViewModel(repository, authManager)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads all personas`() {
        val state = viewModel.uiState.value
        assertEquals(4, state.size)
    }

    @Test
    fun `filterByCategory filters by trait match`() {
        viewModel.filterByCategory("Sci-Fi")
        val state = viewModel.uiState.value
        assertEquals(1, state.size)
        assertEquals("Aetheris", state.first().name)
    }

    @Test
    fun `filterByCategory All returns everything`() {
        viewModel.filterByCategory(null)
        val state = viewModel.uiState.value
        assertEquals(4, state.size)
    }

    @Test
    fun `filterByCategory with no match returns empty`() {
        viewModel.filterByCategory("Fantasy")
        val state = viewModel.uiState.value
        assertEquals(0, state.size)
    }

    @Test
    fun `searchPersonas matches by name`() {
        viewModel.searchPersonas("Glitch")
        val state = viewModel.uiState.value
        assertEquals(1, state.size)
    }

    @Test
    fun `searchPersonas matches by backstory`() {
        viewModel.searchPersonas("wanderer")
        val state = viewModel.uiState.value
        assertEquals(1, state.size)
        assertEquals("Nova", state.first().name)
    }

    @Test
    fun `searchPersonas with empty query returns all`() {
        viewModel.searchPersonas("")
        val state = viewModel.uiState.value
        assertEquals(4, state.size)
    }

    @Test
    fun `searchPersonas is case-insensitive`() {
        viewModel.searchPersonas("aetheris")
        val state = viewModel.uiState.value
        assertEquals(1, state.size)
    }

    @Test
    fun `filter and search work together`() {
        viewModel.filterByCategory("Space")
        viewModel.searchPersonas("Cosmic")
        val state = viewModel.uiState.value
        assertEquals(1, state.size)
        assertEquals("Nova", state.first().name)
    }

    @Test
    fun `categories are trimmed distinct actual traits including a trait named All`() = runTest {
        whenever(repository.getPersonas()).thenReturn(listOf(personas[0].copy(
            traits = listOf(" Kind ", "kind", "", "  ", "Curious", "All")
        )))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("All", "Curious", "Kind"), viewModel.categories.value)
        viewModel.filterByCategory("All")
        assertEquals(1, viewModel.uiState.value.size)
        viewModel.filterByCategory("kind")
        assertEquals(1, viewModel.uiState.value.size)
    }

    @Test
    fun `refresh reflects edits and creations while retaining query and category`() = runTest {
        viewModel.filterByCategory("Space")
        viewModel.searchPersonas("Cosmic")
        val edited = personas[1].copy(backstory = "Edited Cosmic explorer")
        val created = edited.copy(id = "5", name = "New Cosmic persona")
        whenever(repository.getPersonas()).thenReturn(listOf(edited, created, personas[0]))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(edited, created), viewModel.uiState.value)
        assertEquals("Space", viewModel.selectedCategory)
        assertEquals("Cosmic", viewModel.searchQuery)
        assertEquals(listOf("Curious", "Sci-Fi", "Smart", "Space"), viewModel.categories.value)
    }

    @Test
    fun `filter changes during refresh apply to newly loaded data`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getPersonas()).doSuspendableAnswer { response.await() }
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        viewModel.filterByCategory("Space")
        viewModel.searchPersonas("new")
        val created = personas[1].copy(name = "New Nova")
        response.complete(listOf(created, personas[1]))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(created), viewModel.uiState.value)
    }

    @Test
    fun `late refresh cannot replace newer list or categories`() = runTest {
        val oldResponse = CompletableDeferred<List<Persona>>()
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else listOf(personas[1])
        }
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        oldResponse.complete(listOf(personas[0]))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(personas[1]), viewModel.uiState.value)
        assertEquals(listOf("Curious", "Space"), viewModel.categories.value)
    }

    @Test
    fun `account change clears list and categories and rejects late response`() = runTest {
        val oldResponse = CompletableDeferred<List<Persona>>()
        val newResponse = CompletableDeferred<List<Persona>>()
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
        }
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("token-b", "2099-01-01T00:00:00Z", AuthUserDto("b", "b@test.dev"))
        )
        authManager.signIn("b@test.dev", "password")
        testDispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        assertEquals(emptyList<String>(), viewModel.categories.value)

        newResponse.complete(listOf(personas[1]))
        testDispatcher.scheduler.runCurrent()
        oldResponse.complete(listOf(personas[0]))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(personas[1]), viewModel.uiState.value)
        assertEquals(listOf("Curious", "Space"), viewModel.categories.value)
    }

    @Test
    fun `filter changes cannot republish a previous account before identity collection`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getPersonas()).doSuspendableAnswer { response.await() }
        authManager.logout()
        viewModel.searchPersonas("Nova")
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        assertEquals(emptyList<String>(), viewModel.categories.value)
        viewModel.filterByCategory("Space")
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        testDispatcher.scheduler.runCurrent()
        response.complete(listOf(personas[1]))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(personas[1]), viewModel.uiState.value)
        assertEquals("Nova", viewModel.searchQuery)
        assertEquals("Space", viewModel.selectedCategory)
    }

    @Test
    fun `refresh after session replacement clears cached results synchronously`() = runTest {
        val response = CompletableDeferred<List<Persona>>()
        whenever(repository.getPersonas()).doSuspendableAnswer { response.await() }
        authManager.signIn("a@test.dev", "password")
        viewModel.refresh()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        assertEquals(emptyList<String>(), viewModel.categories.value)
        testDispatcher.scheduler.runCurrent()
        response.complete(listOf(personas[0]))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(personas[0]), viewModel.uiState.value)
    }

    @Test
    fun `removed selected trait remains selected until explicitly cleared`() = runTest {
        viewModel.filterByCategory("Space")
        whenever(repository.getPersonas()).thenReturn(listOf(personas[0]))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Persona>(), viewModel.uiState.value)
        assertEquals("Space", viewModel.selectedCategory)
        assertEquals(listOf("Sci-Fi", "Smart"), viewModel.categories.value)
        viewModel.filterByCategory(null)
        assertEquals(listOf(personas[0]), viewModel.uiState.value)
    }

    @Test
    fun `failed refresh retains filters and data and reports the error`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        viewModel.filterByCategory("Space")
        viewModel.searchPersonas("Cosmic")
        val categories = viewModel.categories.value
        whenever(repository.getPersonas()).doSuspendableAnswer { error("Load failed") }
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(personas[1]), viewModel.uiState.value)
        assertEquals(categories, viewModel.categories.value)
        assertEquals("Space", viewModel.selectedCategory)
        assertEquals("Cosmic", viewModel.searchQuery)
        assertEquals(listOf("Load failed"), errors)
    }

    @Test
    fun `superseded failure cannot replace results or report a stale error`() = runTest {
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errorEvents.collect { errors += it }
        }
        val response = CompletableDeferred<Unit>()
        var calls = 0
        whenever(repository.getPersonas()).doSuspendableAnswer {
            if (calls++ == 0) withContext(NonCancellable) {
                response.await()
                error("Obsolete request failed")
            } else listOf(personas[1])
        }
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        response.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(personas[1]), viewModel.uiState.value)
        assertEquals(listOf("Curious", "Space"), viewModel.categories.value)
        assertTrue(errors.isEmpty())
    }
}
