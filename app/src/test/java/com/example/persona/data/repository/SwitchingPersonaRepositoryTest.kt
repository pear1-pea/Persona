package com.example.persona.data.repository

import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.any
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import java.io.IOException

class SwitchingPersonaRepositoryTest {

    private val roomImpl: RoomPersonaRepository = mock()
    private val remoteImpl: RemotePersonaRepository = mock()
    private val tokenStore: SessionTokenStore = mock()
    private val authApi: AuthApi = mock()
    private lateinit var authManager: AuthManager
    private lateinit var repo: SwitchingPersonaRepository

    private val roomPersona = Persona("r1", "Room", "", "", listOf("B"), "from room", "me")
    private val remotePersona = Persona("m1", "Remote", "", "", listOf("A"), "from remote", "user-1")

    @Before
    fun setUp() {
        authManager = AuthManager(authApi, tokenStore, mock())
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
        // With no stored session the local source remains independent.
        whenever(roomImpl.getMyPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getMyPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getMyPersonas()
    }

    @Test
    fun `old remote failure after logout cannot fall back into offline source`() = runTest {
        login()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(remoteImpl.getPersonas()).doSuspendableAnswer {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
            throw IOException("old request failed")
        }
        val result = async { runCatching { repo.getPersonas() } }
        started.await()
        authManager.logout()
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        verify(roomImpl, never()).getPublicSeedPersonas()
    }

    @Test
    fun `logged in lookup cannot merge offline persona with same id`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("same-id")).thenReturn(null)
        whenever(roomImpl.getPublicSeedPersonaById("same-id")).thenReturn(null)
        whenever(roomImpl.getPersonaById("same-id")).thenReturn(roomPersona.copy(id = "same-id"))
        assertNull(repo.getPersonaById("same-id"))
        verify(roomImpl, never()).getPersonaById(any())
    }

    private suspend fun login() {
        whenever(authApi.login(org.mockito.kotlin.any())).thenReturn(
            AuthSessionDto("token", "2099-01-01", AuthUserDto("user-1", "user-1@test.dev"))
        )
        authManager.signIn("user-1@test.dev", "password")
    }
}
