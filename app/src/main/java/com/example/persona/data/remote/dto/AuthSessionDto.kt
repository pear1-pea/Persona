package com.example.persona.data.remote.dto

data class AuthCredentials(
    val email: String,
    val password: String
)

data class AuthSessionDto(
    val token: String,
    val expiresAt: String,
    val user: AuthUserDto
)

data class AuthUserDto(
    val id: String,
    val email: String
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

data class DeleteAccountRequest(
    val currentPassword: String
)

data class AuthSessionInfoDto(
    val id: String,
    val createdAt: String,
    val lastUsedAt: String,
    val expiresAt: String,
    val isCurrent: Boolean,
    val userAgent: String? = null,
    val ipAddress: String? = null
)
