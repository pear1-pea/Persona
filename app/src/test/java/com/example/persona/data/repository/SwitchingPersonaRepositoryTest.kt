package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SwitchingPersonaRepositoryTest {

    private val authManager: AuthManager = mock()
    private val cloudImpl: CloudPersonaRepository = mock()
    private val roomImpl: RoomPersonaRepository = mock()
    private lateinit var repo: SwitchingPersonaRepository

    private val cloudPersona = Persona("c1", "Cloud", "", "",  listOf("A"), "from cloud", "me")
    private val roomPersona = Persona("r1", "Room", "", "", listOf("B"), "from room", "me")

    @Before
    fun setUp() {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(false))
        repo = SwitchingPersonaRepository(authManager, cloudImpl, roomImpl)
    }

    @Test
    fun `getMyPersonas uses roomImpl when offline`() = runTest {
        whenever(roomImpl.getMyPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getMyPersonas()

        assertEquals(1, result.size)
        verify(roomImpl).getMyPersonas()
    }

    @Test
    fun `getMyPersonas uses cloudImpl when logged in`() = runTest {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(true))
        whenever(cloudImpl.getMyPersonas()).thenReturn(listOf(cloudPersona))

        val result = repo.getMyPersonas()

        assertEquals(1, result.size)
        verify(cloudImpl).getMyPersonas()
    }

    @Test
    fun `addPersona delegates to room when offline`() = runTest {
        repo.addPersona("Test", listOf("T"), "Test backstory")

        verify(roomImpl).addPersona("Test", listOf("T"), "Test backstory")
    }

    @Test
    fun `addPersona delegates to cloud when logged in`() = runTest {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(true))

        repo.addPersona("Test", listOf("T"), "Test backstory")

        verify(cloudImpl).addPersona("Test", listOf("T"), "Test backstory")
    }

    @Test
    fun `getPersonas returns cloud results when available`() = runTest {
        whenever(cloudImpl.getPersonas()).thenReturn(listOf(cloudPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(cloudPersona), result)
    }

    @Test
    fun `getPersonas falls back to room when cloud fails`() = runTest {
        whenever(cloudImpl.getPersonas()).thenThrow(RuntimeException("Cloud down"))
        whenever(roomImpl.getPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
    }

    @Test
    fun `getPersonas falls back to room when cloud returns empty`() = runTest {
        whenever(cloudImpl.getPersonas()).thenReturn(emptyList())
        whenever(roomImpl.getPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
    }

    @Test
    fun `getPersonaById tries cloud first then room`() = runTest {
        whenever(cloudImpl.getPersonaById("c1")).thenReturn(cloudPersona)
        whenever(roomImpl.getPersonaById("c1")).thenReturn(roomPersona)

        val result = repo.getPersonaById("c1")

        assertEquals(cloudPersona, result)
    }

    @Test
    fun `getPersonaById returns null when neither has it`() = runTest {
        whenever(cloudImpl.getPersonaById("x")).thenReturn(null)
        whenever(roomImpl.getPersonaById("x")).thenReturn(null)

        assertNull(repo.getPersonaById("x"))
    }
}
