package com.example.persona.core.auth

import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthCredentials
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthSessionInfoDto
import com.example.persona.data.remote.dto.ChangePasswordRequest
import com.example.persona.data.remote.dto.DeleteAccountRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(
    val id: String,
    val email: String,
    val nickname: String = email.substringBefore('@')
)

@Singleton
class AuthManager @Inject constructor(
    private val authApi: AuthApi,
    private val sessionTokenStore: SessionTokenStore,
    private val personaDao: PersonaDao
) : AuthTokenProvider {
    private val stateLock = Any()
    private val localDataMutex = Mutex()
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSession = sessionTokenStore.read()
    private val deletedOwners = sessionTokenStore.pendingDeletedOwners().toMutableSet()
    private val _identity = MutableStateFlow(newIdentity())
    private val _isLoggedIn = MutableStateFlow(activeSession != null)
    private val _currentUser = MutableStateFlow(activeSession?.toAuthUser())

    val identity: StateFlow<AuthIdentity> = _identity.asStateFlow()
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()
    val currentUserId: String? get() = synchronized(stateLock) { activeSession?.userId }
    internal val offlineUserId = "OFFLINE_USER"
    val currentRepoId: String get() = identity.value.ownerId

    init {
        if (deletedOwners.isNotEmpty()) {
            synchronized(stateLock) {
                if (activeSession?.userId in deletedOwners) clearSessionLocked()
            }
            authScope.launch { runCatching { retryPendingDeletions() } }
        }
    }

    override fun currentToken(): String? = synchronized(stateLock) { activeSession?.token }

    // The encrypted store is read only on startup, never resurrected after logout.
    override suspend fun refreshToken(): String? = currentToken()

    override fun invalidateSession() = synchronized(stateLock) { clearSessionLocked() }

    override fun invalidateSession(expectedToken: String) = synchronized(stateLock) {
        if (activeSession?.token == expectedToken) clearSessionLocked()
    }

    fun requireIdentity(expected: AuthIdentity) {
        synchronized(stateLock) {
            if (identity.value !== expected || expected.ownerId in deletedOwners) {
                throw CancellationException("Account or session changed")
            }
        }
    }

    suspend fun requestIdentity(): AuthIdentity {
        val context = currentCoroutineContext()
        context.ensureActive()
        return (context[AuthIdentity] ?: identity.value).also(::requireIdentity)
    }

    suspend fun <T> withIdentity(
        expected: AuthIdentity? = null,
        block: suspend () -> T
    ): T = coroutineScope {
        val captured = expected ?: requestIdentity()
        requireIdentity(captured)
        val requestJob = currentCoroutineContext().job
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            identity.first { it !== captured }
            requestJob.cancel(CancellationException("Account or session changed"))
        }
        try {
            withContext(captured) {
                requireIdentity(captured)
                block().also { requireIdentity(captured) }
            }
        } finally {
            observer.cancel()
        }
    }

    // Serialize writes with account deletion; even NonCancellable finalizers keep
    // their original identity and cannot recreate rows after deletion completes.
    suspend fun <T> withLocalIdentity(block: suspend (String) -> T): T {
        val expected = requestIdentity()
        return localDataMutex.withLock {
            requireIdentity(expected)
            block(expected.ownerId)
        }
    }

    suspend fun signIn(email: String, password: String) {
        val expected = identity.value
        val session = authApi.login(AuthCredentials(normalizeEmail(email), password))
        currentCoroutineContext().ensureActive()
        saveSession(session, expected)
    }

    suspend fun signUp(email: String, password: String) {
        val expected = identity.value
        val session = authApi.register(AuthCredentials(normalizeEmail(email), password))
        currentCoroutineContext().ensureActive()
        saveSession(session, expected)
    }

    fun logout() {
        val token = synchronized(stateLock) {
            val token = activeSession?.token
            clearSessionLocked()
            token
        }
        if (!token.isNullOrBlank()) {
            authScope.launch { runCatching { authApi.logout("Bearer $token") } }
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val expected = requestIdentity()
        val token = expected.token ?: error("请重新登录")
        val session = authApi.changePassword(
            "Bearer $token", ChangePasswordRequest(currentPassword, newPassword)
        )
        currentCoroutineContext().ensureActive()
        saveSession(session, expected)
    }

    suspend fun deleteAccount(currentPassword: String) {
        val expected = requestIdentity()
        val token = expected.token ?: error("请重新登录")
        // Once the server accepts deletion, UI cancellation must not interrupt
        // durable cleanup scheduling, session invalidation, or the Room transaction.
        withContext(NonCancellable + Dispatchers.IO) {
            authApi.deleteAccount("Bearer $token", DeleteAccountRequest(currentPassword))
            synchronized(stateLock) { deletedOwners.add(expected.ownerId) }
            var schedulingError: Throwable? = null
            try {
                sessionTokenStore.markAccountDeleted(expected.ownerId)
            } catch (error: Exception) {
                schedulingError = error
            } finally {
                invalidateSession(token)
            }
            try {
                localDataMutex.withLock {
                    personaDao.deleteAccountData(expected.ownerId)
                    sessionTokenStore.completeAccountDeletion(expected.ownerId)
                }
            } catch (error: Exception) {
                schedulingError?.let(error::addSuppressed)
                throw error
            }
        }
    }

    suspend fun retryPendingDeletions() = localDataMutex.withLock {
        sessionTokenStore.pendingDeletedOwners().forEach { owner ->
            require(owner != offlineUserId && owner != "system")
            personaDao.deleteAccountData(owner)
            sessionTokenStore.completeAccountDeletion(owner)
        }
    }

    suspend fun getSessions(): List<AuthSessionInfoDto> = withIdentity {
        authApi.getSessions("Bearer ${requestIdentity().token ?: error("请重新登录")}")
    }

    suspend fun revokeOtherSessions() = withIdentity {
        authApi.revokeOtherSessions("Bearer ${requestIdentity().token ?: error("请重新登录")}")
    }

    suspend fun refreshCurrentUser() = validateSession()

    suspend fun validateSession() {
        val expected = identity.value
        val token = expected.token ?: return
        try {
            val user = authApi.me("Bearer $token")
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (identity.value === expected) {
                    if (user.id == expected.ownerId) {
                        _currentUser.value = AuthUser(user.id, user.email)
                    } else {
                        clearSessionLocked()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) invalidateSession(token)
        } catch (_: Exception) {
            // Keep the current local session on transient validation failures.
        }
    }

    private fun saveSession(session: AuthSessionDto, expected: AuthIdentity) = synchronized(stateLock) {
        requireIdentity(expected)
        require(session.user.id != offlineUserId && session.user.id != "system")
        check(session.user.id !in deletedOwners) { "Account has been deleted" }
        val stored = StoredSession(session.token, session.user.id, session.user.email)
        sessionTokenStore.save(stored)
        activeSession = stored
        _currentUser.value = stored.toAuthUser()
        _isLoggedIn.value = true
        _identity.value = newIdentity()
    }

    private fun clearSessionLocked() {
        activeSession = null
        sessionTokenStore.clear()
        _currentUser.value = null
        _isLoggedIn.value = false
        _identity.value = newIdentity()
    }

    private fun newIdentity() = AuthIdentity(activeSession?.userId ?: "OFFLINE_USER", activeSession?.token)
    private fun normalizeEmail(email: String): String = email.trim().lowercase()
    private fun StoredSession.toAuthUser(): AuthUser = AuthUser(userId, email)
}
