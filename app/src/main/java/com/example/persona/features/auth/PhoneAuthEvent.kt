package com.example.persona.features.auth

sealed interface PhoneAuthEvent {
    data class CodeSent(val verificationId: String) : PhoneAuthEvent
    data object VerificationCompleted : PhoneAuthEvent
    data class VerificationFailed(val message: String) : PhoneAuthEvent
}