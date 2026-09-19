package com.example.persona.data.remote

import com.example.persona.data.remote.dto.CreatePersonaRequest
import com.example.persona.data.remote.dto.PersonaDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.PATCH
import retrofit2.http.POST

interface PersonaBackendApi {
    @GET("api/personas/public")
    suspend fun getPublicPersonas(): List<PersonaDto>

    @GET("api/personas/mine")
    suspend fun getMyPersonas(): List<PersonaDto>

    @GET("api/personas/{id}")
    suspend fun getPersonaById(@Path("id") id: String): PersonaDto?

    @POST("api/personas")
    suspend fun createPersona(@Body request: CreatePersonaRequest): PersonaDto

    @PATCH("api/personas/{id}")
    suspend fun updatePersona(
        @Path("id") id: String,
        @Body request: com.example.persona.data.remote.dto.UpdatePersonaRequest
    ): PersonaDto

    @DELETE("api/personas/{id}")
    suspend fun deletePersona(@Path("id") id: String)
}
