package com.example.persona.features.auth

import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : BaseViewModel() {
    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn

    private val _signInSuccess = MutableSharedFlow<Unit>()
    val signInSuccess: SharedFlow<Unit> = _signInSuccess

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    fun signIn(email: String, password: String) {
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        launchCatching(
            block = {
                authManager.signIn(email, password)
                _signInSuccess.emit(Unit)
            },
            onError = { error -> emitError(error.messageForAuth("登录失败")) }
        ).also {
            it.invokeOnCompletion { _isSubmitting.value = false }
        )
    }

    fun signUp(email: String, password: String) {
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        launchCatching(
            block = {
                authManager.signUp(email, password)
                _signInSuccess.emit(Unit)
            },
            onError = { error -> emitError(error.messageForAuth("注册失败")) }
        ).also {
            it.invokeOnCompletion { _isSubmitting.value = false }
        )
    }

    fun logout() = authManager.logout()

    suspend fun validateSession() = authManager.validateSession()

    private fun Throwable.messageForAuth(prefix: String): String {
        return if (this is HttpException && code() == 409) {
            "该邮箱已注册，请直接登录"
        } else if (this is HttpException && code() == 401) {
            "邮箱或密码错误"
        } else {
            "$prefix，请稍后重试"
        }
    }
}
