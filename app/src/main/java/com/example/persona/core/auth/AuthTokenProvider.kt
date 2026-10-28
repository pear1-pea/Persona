package com.example.persona.core.auth

interface AuthTokenProvider {
    fun currentToken(): String?

    suspend fun refreshToken(): String?

    fun invalidateSession()

    // Implementations with mutable sessions must compare and clear atomically.
    fun invalidateSession(expectedToken: String) {
        if (currentToken() == expectedToken) invalidateSession()
    }
}
