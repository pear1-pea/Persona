package com.example.persona.domain.repository

import com.example.persona.domain.model.Persona

interface PersonaRepository {
    suspend fun getPersonas(): List<Persona>
    suspend fun getPersonaById(id: String): Persona?
    suspend fun getMyPersonas(): List<Persona>
    suspend fun addPersona(name: String, traits: List<String>, backstory: String)

}