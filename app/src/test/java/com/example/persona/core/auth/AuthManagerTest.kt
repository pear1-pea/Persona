package com.example.persona.core.auth

import com.example.persona.data.remote.AuthApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.doSuspendableAnswer
import com.example.persona.data.local.dao.PersonaDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AuthManagerTest {

    private val authApi: AuthApi = mock()
    private val sessionTokenStore: SessionTokenStore = mock()
    private val personaDao: PersonaDao = mock()
    private val authManager = AuthManager(authApi, sessionTokenStore, personaDao)

    @Test
    fun `isLoggedIn returns false when there is no cached Authing user`() {
        assertFalse(authManager.isLoggedIn.value)
    }

    @Test
    fun `currentUserId returns null when not logged in`() {
        assertNull(authManager.currentUserId)
    }

    @Test
    fun `currentRepoId returns OFFLINE_USER when not logged in`() {
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }

    @Test
    fun `late validation success cannot restore previous account`() = runTest {
        val response = CompletableDeferred<AuthUserDto>()
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        authManager.signIn("a@test.dev", "password")
        val started = CompletableDeferred<Unit>()
        whenever(authApi.me(any())).doSuspendableAnswer {
            started.complete(Unit)
            response.await()
        }
        val validation = async { authManager.validateSession() }
        started.await()
        authManager.signIn("b@test.dev", "password")
        response.complete(AuthUserDto("a", "a@test.dev"))
        validation.await()
        assertEquals("b", authManager.currentUserId)
    }

    @Test
    fun `late unauthorized validation cannot clear new session`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        whenever(authApi.me(any())).doSuspendableAnswer {
            started.complete(Unit)
            response.await()
            throw HttpException(Response.error<Unit>(401, "expired".toResponseBody()))
        }
        authManager.signIn("a@test.dev", "password")
        val validation = async { authManager.validateSession() }
        started.await()
        authManager.signIn("b@test.dev", "password")
        response.complete(Unit)
        validation.await()
        assertEquals("token-b", authManager.currentToken())
    }

    @Test
    fun `conditional invalidation ignores old token and clears matching token`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        authManager.signIn("a@test.dev", "password")
        authManager.signIn("b@test.dev", "password")
        authManager.invalidateSession("token-a")
        assertEquals("token-b", authManager.currentToken())
        authManager.invalidateSession("token-b")
        assertNull(authManager.currentToken())
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }

    @Test
    fun `logout cannot reload cached token`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"))
        authManager.signIn("a@test.dev", "password")
        whenever(sessionTokenStore.read()).thenReturn(StoredSession("token-a", "a", "a@test.dev"))
        authManager.logout()
        assertNull(authManager.refreshToken())
    }

    @Test
    fun `account switch cancels request and rejects noncancellable stale write`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        authManager.signIn("a@test.dev", "password")
        val started = CompletableDeferred<Unit>()
        val staleWrite = CompletableDeferred<Throwable?>()
        val request = launch {
            authManager.withIdentity {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        staleWrite.complete(runCatching {
                            authManager.withLocalIdentity { error("Old write must never run") }
                        }.exceptionOrNull())
                    }
                }
            }
        }
        started.await()
        authManager.signIn("b@test.dev", "password")
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(staleWrite.await() is CancellationException)
    }

    @Test
    fun `successful deletion schedules and completes local owner cleanup`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"))
        authManager.signIn("a@test.dev", "password")
        authManager.deleteAccount("password")
        verify(personaDao).deleteAccountData("a")
        verify(sessionTokenStore).markAccountDeleted("a")
        verify(sessionTokenStore).completeAccountDeletion("a")
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }

    @Test
    fun `failed remote deletion preserves session and all local data`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"))
        whenever(authApi.deleteAccount(any(), any())).thenThrow(IllegalStateException("offline"))
        authManager.signIn("a@test.dev", "password")
        runCatching { authManager.deleteAccount("password") }
        verify(personaDao, never()).deleteAccountData(any())
        verify(sessionTokenStore, never()).markAccountDeleted(any())
        assertEquals("token-a", authManager.currentToken())
    }

    @Test
    fun `failed local deletion keeps durable pending owner for retry`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"))
        whenever(personaDao.deleteAccountData("a")).thenThrow(IllegalStateException("disk busy"))
        authManager.signIn("a@test.dev", "password")
        runCatching { authManager.deleteAccount("password") }
        verify(sessionTokenStore).markAccountDeleted("a")
        verify(sessionTokenStore, never()).completeAccountDeletion(any())
        assertNull(authManager.currentToken())
    }

    @Test
    fun `late password response cannot replace next account`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<AuthSessionDto>()
        whenever(authApi.changePassword(any(), any())).doSuspendableAnswer {
            started.complete(Unit)
            response.await()
        }
        authManager.signIn("a@test.dev", "password")
        val passwordChange = async { runCatching { authManager.changePassword("old", "new") } }
        started.await()
        authManager.signIn("b@test.dev", "password")
        response.complete(session("a"))
        assertTrue(passwordChange.await().exceptionOrNull() is CancellationException)
        assertEquals("token-b", authManager.currentToken())
    }

    @Test
    fun `deleting previous account only cleans its owner and keeps next session`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"), session("b"))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        whenever(authApi.deleteAccount(any(), any())).doSuspendableAnswer {
            started.complete(Unit)
            response.await()
        }
        authManager.signIn("a@test.dev", "password")
        val deletion = async { authManager.deleteAccount("password") }
        started.await()
        authManager.signIn("b@test.dev", "password")
        response.complete(Unit)
        deletion.await()
        verify(personaDao).deleteAccountData("a")
        verify(personaDao, never()).deleteAccountData("b")
        verify(personaDao, never()).deleteAccountData("OFFLINE_USER")
        assertEquals("token-b", authManager.currentToken())
    }

    @Test
    fun `UI cancellation cannot interrupt accepted account deletion cleanup`() = runTest {
        whenever(authApi.login(any())).thenReturn(session("a"))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        whenever(authApi.deleteAccount(any(), any())).doSuspendableAnswer {
            started.complete(Unit)
            response.await()
        }
        authManager.signIn("a@test.dev", "password")
        val deletion = launch { authManager.deleteAccount("password") }
        started.await()
        deletion.cancel()
        response.complete(Unit)
        deletion.join()
        verify(personaDao).deleteAccountData("a")
        verify(sessionTokenStore).completeAccountDeletion("a")
        assertNull(authManager.currentToken())
    }

    @Test
    fun `pending account cleanup can be retried without a logged in session`() = runTest {
        whenever(sessionTokenStore.pendingDeletedOwners()).thenReturn(setOf("a"))
        authManager.retryPendingDeletions()
        verify(personaDao).deleteAccountData("a")
        verify(sessionTokenStore).completeAccountDeletion("a")
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }

    private fun session(id: String) = AuthSessionDto("token-$id", "2099-01-01", AuthUserDto(id, "$id@test.dev"))
}
