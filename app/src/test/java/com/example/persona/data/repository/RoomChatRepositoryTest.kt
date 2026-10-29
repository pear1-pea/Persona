package com.example.persona.data.repository

import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.never
import org.mockito.kotlin.argumentCaptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue

class RoomChatRepositoryTest {

    private val messageDao: MessageDao = mock()
    private val tokenStore: SessionTokenStore = mock()
    private val authApi: AuthApi = mock()
    private lateinit var authManager: AuthManager
    private lateinit var repo: RoomChatRepository

    private val samplePersona = Persona("p1", "Test", "", "", listOf("A"), "Backstory", "me")

    @Before
    fun setUp() {
        whenever(tokenStore.read()).thenReturn(StoredSession("token", "me", "me@test.dev"))
        authManager = AuthManager(authApi, tokenStore, mock())
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
    fun `pager created by old session cannot load under next identity`() = runTest {
        val flow = repo.getMessagesStream("p1")
        authManager.logout()
        val error = runCatching { flow.first() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        verify(messageDao, never()).getMessagesByPersonaId(any(), any())
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

    @Test
    fun `stale generation cannot write under next account even in finalizer`() = runTest {
        val original = authManager.identity.value
        whenever(authApi.login(any())).thenReturn(
            AuthSessionDto("next-token", "2099-01-01", AuthUserDto("next", "next@test.dev"))
        )
        authManager.signIn("next@test.dev", "password")
        val error = withContext(NonCancellable + original) {
            runCatching { repo.saveMessage(Message("late", "p1", "partial", false), samplePersona) }
                .exceptionOrNull()
        }
        assertTrue(error is CancellationException)
        verify(messageDao, never()).insertPersonaAndMessage(any(), any(), any())
    }

    @Test
    fun `owner is captured before suspending database write`() = runTest {
        val captured = argumentCaptor<MessageEntity>()
        repo.saveMessage(Message("mine", "p1", "hello", true), samplePersona)
        verify(messageDao).insertPersonaAndMessage(any(), any(), captured.capture())
        assertEquals("me", captured.firstValue.ownerId)
    }
}
