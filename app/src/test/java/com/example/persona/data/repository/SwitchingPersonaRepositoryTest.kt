package com.example.persona.data.repository

import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.repository.MemoryRepository
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
import org.mockito.kotlin.times
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.any
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import java.io.IOException
import retrofit2.HttpException

class SwitchingPersonaRepositoryTest {

    private val memories: MemoryRepository = mock()
    private val roomImpl: RoomPersonaRepository = mock()
    private val remoteImpl: RemotePersonaRepository = mock()
    private val tokenStore: SessionTokenStore = mock()
    private val authApi: AuthApi = mock()
    private lateinit var authManager: AuthManager
    private lateinit var repo: SwitchingPersonaRepository
    private val pendingPersonaDeletions = mutableMapOf<String, MutableSet<String>>()

    private val roomPersona = Persona("r1", "Room", "", "", listOf("B"), "from room", "me")
    private val remotePersona = Persona("m1", "Remote", "", "", listOf("A"), "from remote", "user-1")

    @Before
    fun setUp() {
        authManager = AuthManager(authApi, tokenStore, mock())
        whenever(tokenStore.pendingPersonaDeletions(any())).thenAnswer {
            pendingPersonaDeletions[it.getArgument<String>(0)].orEmpty().toSet()
        }
        doAnswer {
            pendingPersonaDeletions.getOrPut(it.getArgument(0)) { mutableSetOf() }.add(it.getArgument(1))
            Unit
        }.whenever(tokenStore).markPersonaDeletionPending(any(), any())
        doAnswer {
            pendingPersonaDeletions[it.getArgument<String>(0)]?.remove(it.getArgument<String>(1))
            Unit
        }.whenever(tokenStore).completePersonaDeletion(any(), any())
        repo = SwitchingPersonaRepository(roomImpl, remoteImpl, authManager, memories, tokenStore)
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
        whenever(roomImpl.getPersonas()).thenReturn(listOf(roomPersona))

        val result = repo.getPersonas()

        assertEquals(listOf(roomPersona), result)
        verify(roomImpl).getPersonas()
    }

    @Test
    fun `offline public listing includes own public personas but excludes private personas`() = runTest {
        val own = roomPersona.copy(creatorId = "OFFLINE_USER", isPublic = true)
        val seed = roomPersona.copy(id = "seed", creatorId = "system", isPublic = true)
        whenever(roomImpl.getPersonas()).thenReturn(listOf(own, seed, own.copy(id = "private", isPublic = false)))
        assertEquals(listOf(own, seed), repo.getPersonas())
        verify(remoteImpl, never()).getPersonas()
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
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)

        repo.deletePersona("m1")

        verify(remoteImpl).deletePersona("m1")
    }

    @Test
    fun `accepted remote deletion can retry failed memory cleanup after backend returns not found`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)
        val missing: HttpException = mock()
        whenever(missing.code()).thenReturn(404)
        whenever(remoteImpl.deletePersona("m1")).thenReturn(Unit).thenThrow(missing)
        val diskFailure = IllegalStateException("synthetic disk failure")
        whenever(memories.deleteForPersona("m1")).thenThrow(diskFailure).thenReturn(Unit)

        val failure = runCatching { repo.deletePersona("m1") }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it === diskFailure })
        assertNull("Retry must complete local cleanup", runCatching { repo.deletePersona("m1") }.exceptionOrNull())

        verify(memories, times(2)).deleteForPersona("m1")
    }

    @Test
    fun `recreated repository completes pending cleanup when own remote list no longer contains persona`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)
        whenever(memories.deleteForPersona("m1"))
            .thenThrow(IllegalStateException("synthetic disk failure")).thenReturn(Unit)
        assertTrue(runCatching { repo.deletePersona("m1") }.isFailure)
        pendingPersonaDeletions["other-owner"] = mutableSetOf("m1")
        repo = SwitchingPersonaRepository(roomImpl, remoteImpl, authManager, memories, tokenStore)
        whenever(remoteImpl.getMyPersonas()).thenReturn(emptyList())

        assertEquals(emptyList<Persona>(), repo.getMyPersonas())

        assertEquals(emptySet<String>(), tokenStore.pendingPersonaDeletions("user-1"))
        assertEquals(setOf("m1"), tokenStore.pendingPersonaDeletions("other-owner"))
        verify(remoteImpl, times(1)).deletePersona("m1")
        verify(memories, times(2)).deleteForPersona("m1")
    }

    @Test
    fun `cached intent after a failed durable write cannot bypass persistence on retry`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)
        var writes = 0
        doAnswer {
            pendingPersonaDeletions.getOrPut("user-1") { mutableSetOf() }.add("m1")
            writes++
            if (writes < 3) throw IllegalStateException("synthetic commit failure after cache update")
            Unit
        }.whenever(tokenStore).markPersonaDeletionPending("user-1", "m1")

        repeat(2) { assertTrue(runCatching { repo.deletePersona("m1") }.isFailure) }
        verify(remoteImpl, never()).deletePersona(any())
        verify(memories, never()).deleteForPersona(any())
        repo.deletePersona("m1")

        assertEquals(3, writes)
        verify(remoteImpl, times(1)).deletePersona("m1")
    }

    @Test
    fun `remote deletion error preserves notes while persona still exists`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)
        val unauthorized: HttpException = mock()
        whenever(unauthorized.code()).thenReturn(401)
        whenever(remoteImpl.deletePersona("m1")).thenThrow(unauthorized)

        assertTrue(runCatching { repo.deletePersona("m1") }.exceptionOrNull() === unauthorized)
        whenever(remoteImpl.getMyPersonas()).thenReturn(listOf(remotePersona))
        assertEquals(listOf(remotePersona), repo.getMyPersonas())

        verify(memories, never()).deleteForPersona(any())
        assertEquals(setOf("m1"), tokenStore.pendingPersonaDeletions("user-1"))
    }

    @Test
    fun `another creators persona cannot schedule deletion or clear current owners notes`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("other")).thenReturn(remotePersona.copy(id = "other", creatorId = "other-owner"))

        assertTrue(runCatching { repo.deletePersona("other") }.exceptionOrNull() is IllegalStateException)

        verify(remoteImpl, never()).deletePersona(any())
        verify(tokenStore, never()).markPersonaDeletionPending(any(), any())
        verify(memories, never()).deleteForPersona(any())
    }

    @Test
    fun `logout after remote dispatch preserves original owners pending cleanup`() = runTest {
        login()
        whenever(remoteImpl.getPersonaById("m1")).thenReturn(remotePersona)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(remoteImpl.deletePersona("m1")).doSuspendableAnswer {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
        }
        val deletion = async { runCatching { repo.deletePersona("m1") } }
        started.await()
        authManager.logout()
        release.complete(Unit)

        assertTrue(deletion.await().exceptionOrNull() is CancellationException)
        assertEquals(setOf("m1"), tokenStore.pendingPersonaDeletions("user-1"))
        verify(memories, never()).deleteForPersona(any())
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
