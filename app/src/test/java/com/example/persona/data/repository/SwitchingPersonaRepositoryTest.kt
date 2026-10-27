package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

class SwitchingPersonaRepositoryTest {

    private val roomImpl: RoomPersonaRepository = mock()
    private val remoteImpl: RemotePersonaRepository = mock()
    private val authManager: AuthManager = mock()
    private lateinit var repo: SwitchingPersonaRepository

    private val roomPersona = Persona("r1", "Room", "", "", listOf("B"), "from room", "me")
    private val remotePersona = Persona("m1", "Remote", "", "", listOf("A"), "from remote", "user-1")

    @Before
    fun setUp() {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(false))
        repo = SwitchingPersonaRepository(roomImpl, remoteImpl, authManager)
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
        repo.addPersona("Test", listOf("T"), "Test backstory", isPublic = true)

        verify(roomImpl).addPersona("Test", listOf("T"), "Test backstory", true)
    }

    @Test
    fun `getPersonas delegates to room`() = runTest {
        whenever(roomImpl.getPublicSeedPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getPublicSeedPersonas()
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

    @Test
    fun `getMyPersonas delegates to remote when logged in`() = runTest {
        login()
        whenever(remoteImpl.getMyPersonas()).thenReturn(listOf(remotePersona))

        val result = repo.getMyPersonas()

        assertEquals(listOf(remotePersona), result)
        verify(remoteImpl).getMyPersonas()
    }

    @Test
    fun `addPersona delegates to remote when logged in`() = runTest {
        login()

        repo.addPersona("Test", listOf("T"), "Test backstory", isPublic = true)

        verify(remoteImpl).addPersona("Test", listOf("T"), "Test backstory", true)
    }

    @Test
    fun `updatePersona delegates to remote when logged in`() = runTest {
        login()

        repo.updatePersona("m1", "Updated", listOf("U"), "Updated backstory", isPublic = false)

        verify(remoteImpl).updatePersona("m1", "Updated", listOf("U"), "Updated backstory", false)
    }

    @Test
    fun `deletePersona delegates to remote when logged in`() = runTest {
        login()

        repo.deletePersona("m1")

        verify(remoteImpl).deletePersona("m1")
    }

    @Test
    fun `getPersonas delegates to remote when logged in`() = runTest {
        login()
        whenever(remoteImpl.getPersonas()).thenReturn(listOf(remotePersona))

        val result = repo.getPersonas()

        assertEquals(listOf(remotePersona), result)
        verify(remoteImpl).getPersonas()
    }

    @Test
    fun `getPersonas falls back to room seeds when remote fails`() = runTest {
        login()
        whenever(remoteImpl.getPersonas()).thenAnswer { throw IOException("offline") }
        whenever(roomImpl.getPublicSeedPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getPublicSeedPersonas()
    }

    @Test
    fun `getPersonaById returns remote record when logged in`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)

        assertEquals(remotePersona, repo.getPersonaById("m1"))
    }

    @Test
    fun `getPersonaById falls back to room seed when remote returns null`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("r1")).thenReturn(null)
        whenever(roomImpl.getPublicSeedPersonaById("r1")).thenReturn(roomPersona)

        assertEquals(roomPersona, repo.getPersonaById("r1"))
        verify(roomImpl).getPublicSeedPersonaById("r1")
    }

    @Test
    fun `getPersonaById falls back to room seed when remote throws`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("r1")).thenAnswer { throw IOException("offline") }
        whenever(roomImpl.getPublicSeedPersonaById("r1")).thenReturn(roomPersona)

        assertEquals(roomPersona, repo.getPersonaById("r1"))
    }

    @Test
    fun `logged in without a user id remains on the room source`() = runTest {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(true))
        whenever(authManager.currentUserId).thenReturn(null)
        whenever(roomImpl.getMyPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getMyPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getMyPersonas()
    }

    private fun login() {
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(true))
        whenever(authManager.currentUserId).thenReturn("user-1")
    }
}
