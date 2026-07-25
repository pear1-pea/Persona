package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoomPersonaRepositoryTest {

    private val dao: PersonaDao = mock()
    private val authManager: AuthManager = mock()
    private lateinit var repo: RoomPersonaRepository

    private val personaEntity = PersonaEntity("p1", "Test", "avatar", "post", "backstory", "user-1")
    private val traitEntities = listOf(TraitEntity(personaId = "p1", traitContent = "Trait1"))
    private val personaWithTraits = PersonaWithTraits(persona = personaEntity, traits = traitEntities)

    @Before
    fun setUp() {
        whenever(authManager.currentRepoId).thenReturn("user-1")
        repo = RoomPersonaRepository(dao, authManager)
    }

    @Test
    fun `getPersonas returns all personas mapped to domain`() = runTest {
        whenever(dao.getAllPersonas()).thenReturn(listOf(personaWithTraits))

        val result = repo.getPersonas()

        assertEquals(1, result.size)
        assertEquals("p1", result.first().id)
        assertEquals("Test", result.first().name)
        assertEquals(listOf("Trait1"), result.first().traits)
    }

    @Test
    fun `getMyPersonas filters by current user`() = runTest {
        whenever(dao.getPersonasByCreator("user-1")).thenReturn(listOf(personaWithTraits))

        val result = repo.getMyPersonas()

        assertEquals(1, result.size)
        verify(dao).getPersonasByCreator("user-1")
    }

    @Test
    fun `getPersonaById returns persona when found`() = runTest {
        whenever(dao.getPersonaById("p1")).thenReturn(personaWithTraits)

        val result = repo.getPersonaById("p1")

        assertNotNull(result)
        assertEquals("Test", result?.name)
    }

    @Test
    fun `getPersonaById returns null when not found`() = runTest {
        whenever(dao.getPersonaById("unknown")).thenReturn(null)

        val result = repo.getPersonaById("unknown")

        assertNull(result)
    }

    @Test
    fun `addPersona inserts persona with traits`() = runTest {
        repo.addPersona("NewPersona", listOf("A", "B"), "New backstory")

        verify(dao).insertCompletePersona(any<PersonaEntity>(), any<List<TraitEntity>>())
    }
}
