package com.example.persona.features.auth

sealed interface PhoneAuthEvent {
    data class CodeSent(val phoneNumber: String) : PhoneAuthEvent
    data class VerificationFailed(val message: String) : PhoneAuthEvent
}
