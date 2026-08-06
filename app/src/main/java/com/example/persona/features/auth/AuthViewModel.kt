
package com.example.persona.features.auth

import androidx.lifecycle.viewModelScope
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : BaseViewModel() {

    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn

    private val _signInSuccess = MutableSharedFlow<Unit>()
    val signInSuccess: SharedFlow<Unit> = _signInSuccess

    private val _phoneAuthEvents = MutableSharedFlow<PhoneAuthEvent>()
    val phoneAuthEvents: SharedFlow<PhoneAuthEvent> = _phoneAuthEvents

    private var currentPhoneNumber: String? = null
    private var lastVerificationRequestTime = 0L
    private val verificationCooldown = 60_000L

    fun getCurrentPhoneNumber(): String? = currentPhoneNumber

    fun startPhoneNumberVerification(phoneNumber: String) {
        val now = System.currentTimeMillis()
        if (now - lastVerificationRequestTime < verificationCooldown) {
            viewModelScope.launch {
                _phoneAuthEvents.emit(PhoneAuthEvent.VerificationFailed("Please wait a moment before requesting another code."))
            }
            return
        }

        lastVerificationRequestTime = now
        currentPhoneNumber = phoneNumber

        launchCatching(
            block = {
                authManager.sendPhoneVerificationCode(phoneNumber)
                _phoneAuthEvents.emit(PhoneAuthEvent.CodeSent(phoneNumber))
            },
            onError = { error ->
                viewModelScope.launch {
                    _phoneAuthEvents.emit(
                        PhoneAuthEvent.VerificationFailed(error.localizedMessage ?: "Failed to send verification code.")
                    )
                }
            }
        )
    }

    fun verifyPhoneNumberCode(phoneNumber: String, code: String) {
        launchCatching(
            block = {
                authManager.signInWithPhoneCode(phoneNumber, code)
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                emitError("Phone verification login failed: ${error.localizedMessage ?: "Unknown error"}")
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
                emitError("Login failed: ${error.localizedMessage ?: "Unknown error"}")
            }
        )
    }

    fun signUp(email: String, password: String) {
        launchCatching(
            block = {
                authManager.signUp(email, password)
                _signInSuccess.emit(Unit)
            },
            onError = { error ->
                emitError("Registration failed: ${error.localizedMessage ?: "Unknown error"}")
            }
        )
    }

    fun logout() {
        authManager.logout()
    }
}
