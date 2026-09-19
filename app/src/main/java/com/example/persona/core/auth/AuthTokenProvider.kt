package com.example.persona.core.auth

interface AuthTokenProvider {
    fun currentToken(): String?

    suspend fun refreshToken(): String?

    fun invalidateSession()
}
