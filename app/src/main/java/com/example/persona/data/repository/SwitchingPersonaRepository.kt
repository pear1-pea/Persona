package com.example.persona.data.repository

import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwitchingPersonaRepository @Inject constructor(
    private val roomImpl: RoomPersonaRepository 
) : PersonaRepository {

    private fun getActiveRepository(): PersonaRepository {
        return roomImpl
    }

    override suspend fun getPersonas(): List<Persona> {
        return roomImpl.getPersonas()
    }

    override suspend fun getMyPersonas(): List<Persona> {
        return getActiveRepository().getMyPersonas()
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String) {
        getActiveRepository().addPersona(name, traits, backstory)
    }

    override suspend fun getPersonaById(id: String): Persona? {
        return roomImpl.getPersonaById(id)
    }
}
