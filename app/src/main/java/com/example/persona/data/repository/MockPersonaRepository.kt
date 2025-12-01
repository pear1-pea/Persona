package com.example.persona.data.repository

import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockPersonaRepository @Inject constructor() : PersonaRepository {

    private val _personas = mutableListOf(
        Persona(
            "1",
            "Aetheris",
            "https://api.dicebear.com/7.x/bottts/png?seed=Aetheris",
            "https://picsum.photos/seed/1/800/600", 
            listOf("Sci-Fi", "Witty"),
            "My AI Twin",
            "me"
        ),
        Persona(
            "2",
            "Cyber-Bard",
            "https://api.dicebear.com/7.x/bottts/png?seed=CyberBard",
            "https://picsum.photos/seed/2/800/600", 
            listOf("Sci-Fi", "Poetic"),
            "Cyberpunk Poet",
            "me"
        ),
        Persona(
            "3",
            "Nova",
            "https://api.dicebear.com/7.x/avataaars/png?seed=Nova",
            "https://picsum.photos/seed/3/800/600", 
            listOf("Sci-Fi", "Space"),
            "Space Explorer",
            "system"
        ),
        Persona(
            "4",
            "Glitch",
            "https://api.dicebear.com/7.x/pixel-art/png?seed=Glitch",
            "https://picsum.photos/seed/4/800/600", 
            listOf("Realistic", "Dark"),
            "Digital Ghost",
            "system"
        ),
        Persona(
            "5",
            "Oracle",
            "https://api.dicebear.com/7.x/notionists/png?seed=Oracle",
            "https://picsum.photos/seed/5/800/600", 
            listOf("Fantasy", "Magic"),
            "Ancient Wizard",
            "system"
        )
    )

    override suspend fun getPersonas(): List<Persona> = _personas

    override suspend fun getMyPersonas(): List<Persona> {
        return _personas.filter { it.creatorId == "me" }
    }

    override suspend fun getPersonaById(id: String): Persona? {
        return _personas.find { it.id == id }
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String) {
        val id = UUID.randomUUID().toString()
        val newPersona = Persona(
            id = id,
            name = name,
            avatarUrl = "https://api.dicebear.com/7.x/bottts/png?seed=$name",
            postImageUrl = "https://picsum.photos/seed/$id/800/600", 
            traits = traits,
            backstory = backstory,
            creatorId = "me"
        )
        _personas.add(0, newPersona)
    }
}