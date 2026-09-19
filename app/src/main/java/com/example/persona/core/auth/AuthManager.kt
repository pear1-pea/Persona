package com.example.persona.core.auth

import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthCredentials
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthSessionInfoDto
import com.example.persona.data.remote.dto.ChangePasswordRequest
import com.example.persona.data.remote.dto.DeleteAccountRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val sessionTokenStore: SessionTokenStore
) : AuthTokenProvider {
    private val sessionMutex = Mutex()
    @Volatile
    private var activeSession = sessionTokenStore.read()
    private val _isLoggedIn = MutableStateFlow(activeSession != null)
    private val _currentUser = MutableStateFlow(activeSession?.toAuthUser())

    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()
    val currentUserId: String? get() = _currentUser.value?.id

    internal val offlineUserId = "OFFLINE_USER"
    val currentRepoId: String get() = currentUserId ?: offlineUserId

    override fun currentToken(): String? = activeSession?.token

    override suspend fun refreshToken(): String? = sessionMutex.withLock {
        val session = activeSession ?: sessionTokenStore.read()
        if (session == null) {
            clearSession()
            null
        } else {
            _currentUser.value = session.toAuthUser()
            _isLoggedIn.value = true
            session.token
        }
    }

    override fun invalidateSession() {
        clearSession()
    }

    suspend fun signIn(email: String, password: String) {
        saveSession(authApi.login(AuthCredentials(normalizeEmail(email), password)))
    }

    suspend fun signUp(email: String, password: String) {
        saveSession(authApi.register(AuthCredentials(normalizeEmail(email), password)))
    }

    fun logout() {
        val token = currentToken()
        clearSession()
        if (!token.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { authApi.logout("Bearer $token") }
            }
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val token = requireToken()
        saveSession(
            authApi.changePassword(
                "Bearer $token",
                ChangePasswordRequest(currentPassword, newPassword)
            )
        )
    }

    suspend fun deleteAccount(currentPassword: String) {
        val token = requireToken()
        authApi.deleteAccount("Bearer $token", DeleteAccountRequest(currentPassword))
        clearSession()
    }

    suspend fun getSessions(): List<AuthSessionInfoDto> {
        return authApi.getSessions("Bearer ${requireToken()}")
    }

    suspend fun revokeOtherSessions() {
        authApi.revokeOtherSessions("Bearer ${requireToken()}")
    }

    suspend fun refreshCurrentUser() {
        validateSession()
    }

    suspend fun validateSession() {
        val session = activeSession ?: return
        try {
            val user = authApi.me("Bearer ${session.token}")
            _currentUser.value = AuthUser(user.id, user.email)
            _isLoggedIn.value = true
        } catch (error: HttpException) {
            if (error.code() == 401) invalidateSession() else restoreLocalSession()
        } catch (_: Exception) {
            restoreLocalSession()
        }
    }

    private fun saveSession(session: AuthSessionDto) {
        val storedSession = StoredSession(
            token = session.token,
            userId = session.user.id,
            email = session.user.email
        )
        sessionTokenStore.save(storedSession)
        activeSession = storedSession
        _currentUser.value = AuthUser(session.user.id, session.user.email)
        _isLoggedIn.value = true
    }

    private fun clearSession() {
        activeSession = null
        sessionTokenStore.clear()
        _currentUser.value = null
        _isLoggedIn.value = false
    }

    private fun restoreLocalSession() {
        activeSession?.let { session ->
            _currentUser.value = session.toAuthUser()
            _isLoggedIn.value = true
        }
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun requireToken(): String {
        return currentToken() ?: throw IllegalStateException("请重新登录")
    }

    private fun StoredSession.toAuthUser(): AuthUser = AuthUser(userId, email)
}
