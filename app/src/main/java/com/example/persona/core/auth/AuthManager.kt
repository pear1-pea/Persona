
package com.example.persona.core.auth

import android.util.Log
import cn.authing.guard.AuthCallback
import cn.authing.guard.Authing
import cn.authing.guard.data.UserInfo
import cn.authing.guard.network.AuthClient
import com.example.persona.core.util.UsernameGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AuthUser(
    val id: String? = null,
    val sub: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    val name: String? = null,
    val photo: String? = null,
    val picture: String? = null,
)

@Singleton
class AuthManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialUser = Authing.getCurrentUser()?.toAuthUser()

    private val _isLoggedIn = MutableStateFlow(initialUser != null)
    private val _currentUser = MutableStateFlow(initialUser)

    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()

    val currentUserId: String?
        get() = _currentUser.value?.sub
            ?: _currentUser.value?.id
            ?: _currentUser.value?.email
            ?: _currentUser.value?.phoneNumber
            ?: _currentUser.value?.username

    internal val offlineUserId = "OFFLINE_USER"
    val currentRepoId: String get() = currentUserId ?: offlineUserId

    init {
        initialUser?.let { user ->
            scope.launch {
                ensureDisplayName(user)
            }
        }
    }

    suspend fun signIn(account: String, password: String) {
        val userInfo = awaitUserInfo { callback ->
            AuthClient.loginByAccount(account, password, false, null, callback)
        }
        updateCurrentUser(userInfo.toAuthUser())
    }

    suspend fun signUp(email: String, password: String) {
        val userInfo = awaitUserInfo { callback ->
            AuthClient.registerByEmail(email, password, null, callback)
        }
        updateCurrentUser(userInfo.toAuthUser())
    }

    suspend fun sendPhoneVerificationCode(phoneNumber: String) {
        val (countryCode, phone) = normalizePhoneNumber(phoneNumber)
        awaitOperation { callback ->
            AuthClient.sendSms(countryCode, phone, callback)
        }
    }

    suspend fun signInWithPhoneCode(phoneNumber: String, code: String) {
        val (countryCode, phone) = normalizePhoneNumber(phoneNumber)
        val userInfo = awaitUserInfo { callback ->
            AuthClient.loginByPhoneCode(countryCode, phone, code, false, null, callback)
        }
        updateCurrentUser(userInfo.toAuthUser())
    }

    suspend fun refreshCurrentUser(fallback: AuthUser? = null) {
        if (Authing.getCurrentUser() == null) {
            if (fallback != null) {
                updateCurrentUser(fallback)
            } else {
                clearSession()
            }
            return
        }

        val user = runCatching {
            awaitUserInfo { callback ->
                AuthClient.getCurrentUser(callback)
            }.toAuthUser()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load Authing profile", error)
            fallback ?: Authing.getCurrentUser()?.toAuthUser()
        }

        if (user != null) {
            updateCurrentUser(user)
        } else {
            clearSession()
        }
    }

    fun logout() {
        runCatching {
            AuthClient.logout(object : AuthCallback<Any?> {
                override fun call(code: Int, message: String, data: Any?) {
                    if (code != 200) {
                        Log.w(TAG, "Authing logout failed: $message")
                    }
                    clearSession()
                }
            })
        }.onFailure { error ->
            Log.w(TAG, "Authing logout invocation failed", error)
            clearSession()
        }
    }

    private fun updateCurrentUser(user: AuthUser) {
        _currentUser.value = user
        _isLoggedIn.value = true
        ensureDisplayName(user)
    }

    private fun clearSession() {
        _currentUser.value = null
        _isLoggedIn.value = false
    }

    private fun ensureDisplayName(user: AuthUser) {
        if (!user.nickname.isNullOrBlank() || !user.name.isNullOrBlank()) {
            return
        }

        val generatedName = UsernameGenerator.generate()
        _currentUser.value = user.copy(nickname = generatedName, name = generatedName)

        scope.launch {
            runCatching {
                val body = JSONObject().apply {
                    put("nickname", generatedName)
                    put("name", generatedName)
                }
                val updatedUser = awaitUserInfo { callback ->
                    AuthClient.updateProfile(body, callback)
                }
                _currentUser.value = updatedUser.toAuthUser()
            }.onFailure { error ->
                Log.w(TAG, "Unable to persist generated Authing profile name", error)
            }
        }
    }

    private fun normalizePhoneNumber(raw: String): Pair<String, String> {
        val cleaned = raw.trim().replace(" ", "").replace("-", "")
        return when {
            cleaned.startsWith("+86") -> "+86" to cleaned.removePrefix("+86")
            cleaned.startsWith("86") && cleaned.length > 11 -> "+86" to cleaned.removePrefix("86")
            else -> "+86" to cleaned
        }
    }

    private suspend fun awaitUserInfo(
        block: (AuthCallback<UserInfo>) -> Unit,
    ): UserInfo = suspendCancellableCoroutine { continuation ->
        try {
            block(object : AuthCallback<UserInfo> {
                override fun call(code: Int, message: String, data: UserInfo) {
                    if (!continuation.isActive) {
                        return
                    }

                    if (code == 200) {
                        continuation.resume(data)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException(
                                message.ifBlank { "Authing operation failed ($code)" }
                            )
                        )
                    }
                }
            })
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    private suspend fun awaitOperation(
        block: (AuthCallback<Any?>) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        try {
            block(object : AuthCallback<Any?> {
                override fun call(code: Int, message: String, data: Any?) {
                    if (!continuation.isActive) {
                        return
                    }

                    if (code == 200) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException(
                                message.ifBlank { "Authing operation failed ($code)" }
                            )
                        )
                    }
                }
            })
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }

    private fun UserInfo.toAuthUser(): AuthUser {
        return AuthUser(
            id = id,
            sub = sub,
            email = email,
            phoneNumber = getPhone_number(),
            username = username,
            nickname = nickname,
            name = name,
            photo = photo,
            picture = picture,
        )
    }

    companion object {
        private const val TAG = "AuthManager"
    }
}
