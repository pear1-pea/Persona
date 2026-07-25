package com.example.persona.data.repository

import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoomChatRepositoryTest {

    private val messageDao: MessageDao = mock()
    private val personaDao: PersonaDao = mock()
    private lateinit var repo: RoomChatRepository

    private val samplePersona = Persona("p1", "Test", "", "", listOf("A"), "Backstory", "me")
    private val personaEntity = PersonaEntity("p1", "Test", "", "", "Backstory", "me")
    private val personaWithTraits = PersonaWithTraits(persona = personaEntity, traits = emptyList())
    private val messageEntity = MessageEntity("m1", "p1", "Hello", true, 1000L)

    @Before
    fun setUp() {
        repo = RoomChatRepository(messageDao, personaDao)
    }

    @Test
    fun `saveMessage inserts persona and message`() = runTest {
        val message = Message("m1", "p1", "Hello", true, 1000L)

        repo.saveMessage(message, samplePersona)

        verify(personaDao).insertCompletePersona(any<PersonaEntity>(), any<List<TraitEntity>>())
        verify(messageDao).insertMessage(any<MessageEntity>())
    }

    @Test
    fun `updateMessageContent updates existing message`() = runTest {
        whenever(messageDao.getMessageById("m1")).thenReturn(messageEntity)

        repo.updateMessageContent("m1", "Updated content")

        verify(messageDao).getMessageById("m1")
        verify(messageDao).updateMessage(
            messageEntity.copy(content = "Updated content")
        )
    }

    @Test
    fun `updateMessageContent skips update when message not found`() = runTest {
        whenever(messageDao.getMessageById("m1")).thenReturn(null)

        repo.updateMessageContent("m1", "Updated content")

        verify(messageDao).getMessageById("m1")
        verify(messageDao, never()).updateMessage(any<MessageEntity>())
    }

    @Test
    fun `getMessagesStream returns paging flow`() {
        val flow = repo.getMessagesStream("p1")

        assert(flow is kotlinx.coroutines.flow.Flow)
    }
}
