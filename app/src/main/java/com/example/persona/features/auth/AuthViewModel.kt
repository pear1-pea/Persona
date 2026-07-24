package com.example.persona.features.auth

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.PhoneVerificationCallback
import com.example.persona.core.base.BaseViewModel
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.PhoneAuthCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.activity.ComponentActivity
import com.example.persona.features.profile.SettingsBottomSheet.Companion.TAG

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : BaseViewModel() {

    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn

    private val _signInSuccess = MutableSharedFlow<Unit>()
    val signInSuccess: SharedFlow<Unit> = _signInSuccess

    private val _phoneAuthEvents = MutableSharedFlow<PhoneAuthEvent>()
    val phoneAuthEvents: SharedFlow<PhoneAuthEvent> = _phoneAuthEvents


    private var currentVerificationId: String? = null
    private var lastVerificationRequestTime = 0L
    private val VERIFICATION_COOLDOWN = 60_000L

    fun setCurrentVerificationId(id: String) {
        this.currentVerificationId = id
    }
    
    fun getCurrentVerificationId(): String? = currentVerificationId

    fun startPhoneNumberVerification(phoneNumber: String, activity: ComponentActivity) {
        val now = System.currentTimeMillis()
        if (now - lastVerificationRequestTime < VERIFICATION_COOLDOWN) {
            viewModelScope.launch {
                _phoneAuthEvents.emit(PhoneAuthEvent.VerificationFailed("请稍后再试"))
            }
            return
        }
        lastVerificationRequestTime = now

        object : PhoneVerificationCallback {
            override fun onCodeSent(verificationId: String) {
                viewModelScope.launch {
                    setCurrentVerificationId(verificationId)
                    _phoneAuthEvents.emit(PhoneAuthEvent.CodeSent(verificationId))
                }
            }

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                viewModelScope.launch {
                    try {
                        // 自动验证成功，直接登录
                        authManager.signInWithCredential(credential) // ← 需要新增这个方法
                        _signInSuccess.emit(Unit)
                        _phoneAuthEvents.emit(PhoneAuthEvent.VerificationCompleted)
                    } catch (e: Exception) {
                        emitError("自动验证失败: ${e.localizedMessage}")
                    }
                }
            }

            override fun onVerificationFailed(e: Exception) {
                viewModelScope.launch {
                    _phoneAuthEvents.emit(
                        PhoneAuthEvent.VerificationFailed(e.localizedMessage ?: "未知错误")
                    )
                }
            }
        }.also { callback ->
            authManager.startPhoneNumberVerification(phoneNumber, callback, activity)
        }
    }

    fun verifyPhoneNumberCode(verificationId: String, code: String) {
        Log.d(TAG, "verifyPhoneNumberCode called with verificationId: $verificationId")
        launchCatching(
            block = {
                Log.d(TAG, "Calling authManager.signInWithPhoneNumber")
                authManager.signInWithPhoneNumber(verificationId, code)
                Log.d(TAG, "Sign in with phone number successful, emitting success")
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                Log.e(TAG, "Error verifying code: ${error.message}", error)
                val errorMessage = when (error) {
                    is FirebaseAuthInvalidCredentialsException -> {
                        Log.e(TAG, "Invalid verification code or session info", error)
                        "验证码无效或已过期，请重新获取"
                    }
                    else -> {
                        Log.e(TAG, "Verification failed", error)
                        "验证失败: ${error.message ?: "未知错误"}"
                    }
                }
                emitError(errorMessage)
            }
        )
    }
    fun signInWithGoogle(idToken: String) {
        launchCatching(
            block = {
                authManager.signInWithGoogle(idToken)
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                emitError("Google 登录失败，请重试")
            }
        )
    }

    fun signInAnonymously() {
        launchCatching(
            block = {
                authManager.signInAnonymously()
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                emitError("游客模式启动失败: ${error.localizedMessage}")
            }
        )
    }

    fun signIn(email: String, password: String) {
        launchCatching(
            block = {
                authManager.signIn(email, password)
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                val message = when (error) {
                    is FirebaseAuthInvalidUserException -> "该用户不存在或已被禁用。"
                    is FirebaseAuthInvalidCredentialsException -> "邮箱或密码错误，请检查输入。"
                    else -> "登录失败: ${error.localizedMessage}"
                }
                emitError(message)
            }
        )
    }

    // 注册
    fun signUp(email: String, password: String) {
        launchCatching(
            block = {
                authManager.signUp(email, password)
            },
            onError = { error ->
                val message = when (error) {
                    is FirebaseAuthWeakPasswordException -> "密码强度不足，请设置至少6位密码。"
                    is FirebaseAuthUserCollisionException -> "该邮箱已被注册，请直接登录。"
                    else -> "注册失败: ${error.localizedMessage}"
                }
                emitError(message)
            }
        )
    }

}