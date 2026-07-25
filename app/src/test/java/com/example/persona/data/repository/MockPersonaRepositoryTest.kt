package com.example.persona.data.repository

import com.example.persona.domain.model.Persona
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPersonaRepositoryTest {

    private val repo = MockPersonaRepository()

    @Test
    fun `getPersonas returns all 5 personas`() = runTest {
        val personas = repo.getPersonas()
        assertEquals(5, personas.size)
    }

    @Test
    fun `getMyPersonas returns only creatorId=me`() = runTest {
        val mine = repo.getMyPersonas()
        assertTrue(mine.all { it.creatorId == "me" })
        assertTrue(mine.size < repo.getPersonas().size)
    }

    @Test
    fun `getPersonaById returns correct persona`() = runTest {
        val persona = repo.getPersonaById("1")
        assertNotNull(persona)
        assertEquals("Aetheris", persona?.name)
    }

    @Test
    fun `getPersonaById returns null for unknown id`() = runTest {
        val persona = repo.getPersonaById("non-existent")
        assertNull(persona)
    }

    @Test
    fun `addPersona inserts at front`() = runTest {
        val before = repo.getPersonas().size

        repo.addPersona("NewPersona", listOf("Test"), "A new persona")

        val after = repo.getPersonas()
        assertEquals(before + 1, after.size)
        assertEquals("NewPersona", after.first().name)
        assertEquals("me", after.first().creatorId)
    }
}
