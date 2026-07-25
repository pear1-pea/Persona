package com.example.persona.features.discovery

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        whenever(repository.getPersonas()).thenReturn(personas)
        viewModel = DiscoveryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
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
        viewModel.filterByCategory("All")
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
}
