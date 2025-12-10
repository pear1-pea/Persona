package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.mapper.toDomain
import com.example.persona.data.mapper.toEntity
import com.example.persona.data.mapper.toTraitEntities
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPersonaRepository @Inject constructor(
    private val dao: PersonaDao,
    private val authManager: AuthManager
) : PersonaRepository {

    override suspend fun getPersonas(): List<Persona> {
        return dao.getAllPersonas().map { it.toDomain() }
    }

    override suspend fun getMyPersonas(): List<Persona> {
        return dao.getPersonasByCreator(authManager.currentRepoId).map { it.toDomain() }    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String) {
        val newId = UUID.randomUUID().toString()
        val currentUserId = authManager.currentRepoId
        val newPersona = Persona(
            id = newId,
            name = name,
            avatarUrl = "https://api.dicebear.com/7.x/bottts/png?seed=$name", // random avatar
            postImageUrl = "https://picsum.photos/seed/$newId/800/600",
            traits = traits,
            backstory = backstory,
            creatorId = currentUserId
        )
        dao.insertCompletePersona(newPersona.toEntity(), newPersona.toTraitEntities())
    }

    override suspend fun getPersonaById(id: String): Persona? {
        val entity = dao.getPersonaById(id)
        return entity?.toDomain()
    }
}
