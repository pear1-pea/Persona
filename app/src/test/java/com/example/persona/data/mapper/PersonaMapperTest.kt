package com.example.persona.data.mapper

import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity
import com.example.persona.domain.model.Persona
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonaMapperTest {

    private val personaEntity = PersonaEntity(
        id = "p-1",
        name = "TestPersona",
        avatarUrl = "https://example.com/avatar.png",
        postImageUrl = "https://example.com/post.png",
        backstory = "A test persona",
        creatorId = "user-1"
    )

    private val traitEntities = listOf(
        TraitEntity(traitId = 1, personaId = "p-1", traitContent = "Brave"),
        TraitEntity(traitId = 2, personaId = "p-1", traitContent = "Clever")
    )

    private val personaWithTraits = PersonaWithTraits(
        persona = personaEntity,
        traits = traitEntities
    )

    @Test
    fun `toDomain maps persona and flattens traits`() {
        val domain = personaWithTraits.toDomain()

        assertEquals(personaEntity.id, domain.id)
        assertEquals(personaEntity.name, domain.name)
        assertEquals(personaEntity.avatarUrl, domain.avatarUrl)
        assertEquals(personaEntity.postImageUrl, domain.postImageUrl)
        assertEquals(personaEntity.backstory, domain.backstory)
        assertEquals(personaEntity.creatorId, domain.creatorId)
        assertEquals(listOf("Brave", "Clever"), domain.traits)
    }

    @Test
    fun `toEntity preserves persona fields`() {
        val domain = Persona(
            id = "p-2",
            name = "Another",
            avatarUrl = "https://example.com/a.png",
            postImageUrl = "https://example.com/b.png",
            backstory = "Another persona",
            creatorId = "user-2",
            traits = listOf("Fast", "Strong")
        )

        val entity = domain.toEntity()

        assertEquals(domain.id, entity.id)
        assertEquals(domain.name, entity.name)
        assertEquals(domain.backstory, entity.backstory)
    }

    @Test
    fun `toTraitEntities creates one entity per trait`() {
        val domain = Persona(
            id = "p-3",
            name = "TraitTest",
            avatarUrl = "",
            postImageUrl = "",
            backstory = "",
            creatorId = "me",
            traits = listOf("A", "B", "C")
        )

        val entities = domain.toTraitEntities()

        assertEquals(3, entities.size)
        assertEquals("A", entities[0].traitContent)
        assertEquals("B", entities[1].traitContent)
        assertEquals("C", entities[2].traitContent)
        entities.forEach { assertEquals("p-3", it.personaId) }
    }
}
