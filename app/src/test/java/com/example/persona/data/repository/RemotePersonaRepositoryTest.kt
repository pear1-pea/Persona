package com.example.persona.data.repository

import com.example.persona.data.remote.PersonaBackendApi
import com.example.persona.data.remote.dto.CreatePersonaRequest
import com.example.persona.data.remote.dto.PersonaDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RemotePersonaRepositoryTest {

    private val api: PersonaBackendApi = mock()
    private val repository = RemotePersonaRepository(api)

    @Test
    fun `getPersonas maps public backend personas`() = runTest {
        whenever(api.getPublicPersonas()).thenReturn(listOf(sampleDto()))

        val result = repository.getPersonas()

        assertEquals(1, result.size)
        assertEquals("p1", result.single().id)
        assertEquals(listOf("A", "B"), result.single().traits)
    }

    @Test
    fun `getMyPersonas maps authenticated backend personas`() = runTest {
        whenever(api.getMyPersonas()).thenReturn(listOf(sampleDto()))

        val result = repository.getMyPersonas()

        assertEquals("Aetheris", result.single().name)
    }

    @Test
    fun `addPersona sends public flag to backend`() = runTest {
        whenever(api.createPersona(any())).thenReturn(sampleDto())

        repository.addPersona("Aetheris", listOf("A", "B"), "Backstory", isPublic = false)

        verify(api).createPersona(
            CreatePersonaRequest(
                name = "Aetheris",
                traits = listOf("A", "B"),
                backstory = "Backstory",
                isPublic = false
            )
        )
    }

    private fun sampleDto() = PersonaDto(
        id = "p1",
        name = "Aetheris",
        avatarUrl = "avatar",
        postImageUrl = "post",
        traits = listOf("A", "B"),
        backstory = "Backstory",
        creatorId = "system",
        isPublic = true
    )
}
