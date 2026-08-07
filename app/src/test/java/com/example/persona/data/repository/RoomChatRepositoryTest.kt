package com.example.persona.data.repository

import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.local.entity.MessageEntity
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
    private lateinit var repo: RoomChatRepository

    private val samplePersona = Persona("p1", "Test", "", "", listOf("A"), "Backstory", "me")
    private val messageEntity = MessageEntity("m1", "p1", "Hello", true, 1000L)

    @Before
    fun setUp() {
        repo = RoomChatRepository(messageDao)
    }

    @Test
    fun `saveMessage inserts only message`() = runTest {
        val message = Message("m1", "p1", "Hello", true, 1000L)

        repo.saveMessage(message, samplePersona)

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

    @Test
    fun `getRecentMessages returns chronological domain messages`() = runTest {
        val newest = MessageEntity("m2", "p1", "New", false, 2000L)
        val oldest = MessageEntity("m1", "p1", "Old", true, 1000L)
        whenever(messageDao.getRecentMessagesByPersonaId("p1", 2)).thenReturn(listOf(newest, oldest))

        val messages = repo.getRecentMessages("p1", 2)

        assertEquals(listOf("Old", "New"), messages.map { it.content })
        verify(messageDao).getRecentMessagesByPersonaId("p1", 2)
    }
}
