package com.example.persona.data.repository

import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoomChatRepositoryTest {

    private val messageDao: MessageDao = mock()
    private val authManager: AuthManager = mock()
    private lateinit var repo: RoomChatRepository

    private val samplePersona = Persona("p1", "Test", "", "", listOf("A"), "Backstory", "me")

    @Before
    fun setUp() {
        whenever(authManager.currentRepoId).thenReturn("me")
        repo = RoomChatRepository(messageDao, authManager)
    }

    @Test
    fun `saveMessage caches persona and inserts message atomically`() = runTest {
        val message = Message("m1", "p1", "Hello", true, 1000L)

        repo.saveMessage(message, samplePersona)

        verify(messageDao).insertPersonaAndMessage(any(), any(), any())
    }

    @Test
    fun `updateMessageContent updates existing message`() = runTest {
        whenever(messageDao.updateMessageContent("m1", "me", "Updated content", "FAILED"))
            .thenReturn(1)

        repo.updateMessageContent("m1", "Updated content", MessageStatus.FAILED)

        verify(messageDao).updateMessageContent("m1", "me", "Updated content", "FAILED")
    }

    @Test
    fun `updateMessageContent fails when message not found`() = runTest {
        whenever(messageDao.updateMessageContent("m1", "me", "Updated content", "NORMAL"))
            .thenReturn(0)

        val error = runCatching {
            repo.updateMessageContent("m1", "Updated content")
        }.exceptionOrNull()

        assertEquals("Message not found: m1", error?.message)
        verify(messageDao).updateMessageContent("m1", "me", "Updated content", "NORMAL")
    }

    @Test
    fun `getMessagesStream returns paging flow`() {
        val flow = repo.getMessagesStream("p1")

        assert(flow is kotlinx.coroutines.flow.Flow)
    }

    @Test
    fun `getRecentMessages returns chronological domain messages`() = runTest {
        val newest = MessageEntity("m2", "p1", "me", "New", false, 2000L)
        val oldest = MessageEntity("m1", "p1", "me", "Old", true, 1000L)
        whenever(messageDao.getRecentMessagesByPersonaId("p1", "me", 2)).thenReturn(listOf(newest, oldest))

        val messages = repo.getRecentMessages("p1", 2)

        assertEquals(listOf("Old", "New"), messages.map { it.content })
        verify(messageDao).getRecentMessagesByPersonaId("p1", "me", 2)
    }
}
