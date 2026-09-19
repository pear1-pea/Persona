package com.example.persona.data.repository

import com.example.persona.data.remote.PersonaBackendApi
import com.example.persona.data.remote.dto.CreatePersonaRequest
import com.example.persona.data.remote.dto.toDomain
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

@Singleton
class RemotePersonaRepository @Inject constructor(
    private val api: PersonaBackendApi
) : PersonaRepository {
    override suspend fun getPersonas(): List<Persona> {
        return api.getPublicPersonas().map { it.toDomain() }
    }

    override suspend fun getPersonaById(id: String): Persona? {
        return try {
            api.getPersonaById(id)?.toDomain()
        } catch (error: HttpException) {
            if (error.code() == 404) null else throw error
        }
    }

    override suspend fun getMyPersonas(): List<Persona> {
        return api.getMyPersonas().map { it.toDomain() }
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String, isPublic: Boolean) {
        api.createPersona(
            CreatePersonaRequest(
                name = name,
                traits = traits,
                backstory = backstory,
                isPublic = isPublic
            )
        )
    }

    override suspend fun updatePersona(
        id: String,
        name: String,
        traits: List<String>,
        backstory: String,
        isPublic: Boolean
    ) {
        api.updatePersona(
            id,
            CreatePersonaRequest(name, traits, backstory, isPublic)
        )
    }

    override suspend fun deletePersona(id: String) {
        api.deletePersona(id)
    }
}
