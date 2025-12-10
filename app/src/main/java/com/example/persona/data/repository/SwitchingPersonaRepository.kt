package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwitchingPersonaRepository @Inject constructor(
    private val authManager: AuthManager,
    private val cloudImpl: CloudPersonaRepository, 
    private val roomImpl: RoomPersonaRepository 
) : PersonaRepository {

    private fun getActiveRepository(): PersonaRepository {
        return if (authManager.isLoggedIn.value) cloudImpl else roomImpl
    }

    override suspend fun getPersonas(): List<Persona> {
        // Try to fetch from the cloud first.
        val cloudPersonas = try {
            cloudImpl.getPersonas()
        } catch (e: Exception) {
            emptyList()
        }

        // If the cloud is not empty, return its data.
        if (cloudPersonas.isNotEmpty()) {
            return cloudPersonas
        }

        return roomImpl.getPersonas()
    }

    override suspend fun getMyPersonas(): List<Persona> {
        return getActiveRepository().getMyPersonas()
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String) {
        getActiveRepository().addPersona(name, traits, backstory)
    }

    override suspend fun getPersonaById(id: String): Persona? {
        return cloudImpl.getPersonaById(id) ?: roomImpl.getPersonaById(id)
    }
}