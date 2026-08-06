package com.example.persona.data.repository

import com.example.persona.domain.model.Persona
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SwitchingPersonaRepositoryTest {

    private val roomImpl: RoomPersonaRepository = mock()
    private lateinit var repo: SwitchingPersonaRepository

    private val roomPersona = Persona("r1", "Room", "", "", listOf("B"), "from room", "me")

    @Before
    fun setUp() {
        repo = SwitchingPersonaRepository(roomImpl)
    }

    @Test
    fun `getMyPersonas delegates to room`() = runTest {
        whenever(roomImpl.getMyPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getMyPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getMyPersonas()
    }

    @Test
    fun `addPersona delegates to room`() = runTest {
        repo.addPersona("Test", listOf("T"), "Test backstory")

        verify(roomImpl).addPersona("Test", listOf("T"), "Test backstory")
    }

    @Test
    fun `getPersonas delegates to room`() = runTest {
        whenever(roomImpl.getPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getPersonas()
    }

    @Test
    fun `getPersonaById delegates to room`() = runTest {
        whenever(roomImpl.getPersonaById("r1")).thenReturn(roomPersona)

        val result = repo.getPersonaById("r1")

        assertEquals(roomPersona, result)
        verify(roomImpl).getPersonaById("r1")
    }

    @Test
    fun `getPersonaById returns null when room does not have it`() = runTest {
        whenever(roomImpl.getPersonaById("x")).thenReturn(null)

        assertNull(repo.getPersonaById("x"))
    }
}
