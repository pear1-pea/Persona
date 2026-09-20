package com.example.persona.data.remote

import com.example.persona.data.remote.dto.AuthCredentials
import com.example.persona.data.remote.dto.AuthSessionInfoDto
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.data.remote.dto.ChangePasswordRequest
import com.example.persona.data.remote.dto.DeleteAccountRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.POST

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: AuthCredentials): AuthSessionDto

    @POST("api/auth/login")
    suspend fun login(@Body request: AuthCredentials): AuthSessionDto

    @GET("api/auth/me")
    suspend fun me(@Header("Authorization") authorization: String): AuthUserDto

    @POST("api/auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String)

    @POST("api/auth/change-password")
    suspend fun changePassword(
        @Header("Authorization") authorization: String,
        @Body request: ChangePasswordRequest
    ): AuthSessionDto

    @HTTP(method = "DELETE", path = "api/auth/account", hasBody = true)
    suspend fun deleteAccount(
        @Header("Authorization") authorization: String,
        @Body request: DeleteAccountRequest
    )

    @GET("api/auth/sessions")
    suspend fun getSessions(@Header("Authorization") authorization: String): List<AuthSessionInfoDto>

    @POST("api/auth/sessions/revoke-others")
    suspend fun revokeOtherSessions(@Header("Authorization") authorization: String)
}
