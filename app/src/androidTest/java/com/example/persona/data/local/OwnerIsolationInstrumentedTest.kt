package com.example.persona.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerIsolationInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletingPersonaPreservesOtherOwnersAndOfflineMessages() = runBlocking {
        seedSharedPersona()
        database.personaDao().deletePersona("shared", "a")
        assertEquals(emptyList<MessageEntity>(), messages("a"))
        assertEquals(listOf("message-b"), messages("b").map { it.id })
        assertEquals(listOf("message-OFFLINE_USER"), messages("OFFLINE_USER").map { it.id })
    }

    @Test
    fun deletingCreatorPreservesOtherOwnersAndOfflineMessages() = runBlocking {
        seedSharedPersona()
        database.personaDao().deletePersonasByCreator("a")
        assertEquals(listOf("message-b"), messages("b").map { it.id })
        assertEquals(listOf("message-OFFLINE_USER"), messages("OFFLINE_USER").map { it.id })
    }

    @Test
    fun collidingMessageIdCannotReplaceAnotherOwnersMessage() = runBlocking {
        seedSharedPersona()
        runCatching {
            database.messageDao().insertMessage(
                MessageEntity("message-a", "shared", "b", "replacement", false, 2L)
            )
        }
        assertEquals(listOf("original"), messages("a").map { it.content })
    }

    @Test
    fun accountCleanupOnlyDeletesRequestedOwnersMessages() = runBlocking {
        seedSharedPersona()
        database.personaDao().insertPersona(PersonaEntity("other", "Other", "", "", "", "b"))
        database.messageDao().insertMessage(MessageEntity("a-other", "other", "a", "mine", true, 1L))
        database.personaDao().deleteAccountData("a")
        assertEquals(emptyList<MessageEntity>(), messages("a"))
        assertEquals(emptyList<MessageEntity>(),
            database.messageDao().getRecentMessagesByPersonaId("other", "a", 10))
        assertEquals(listOf("message-b"), messages("b").map { it.id })
        assertEquals(listOf("message-OFFLINE_USER"), messages("OFFLINE_USER").map { it.id })
    }

    @Test
    fun anotherOwnerCannotDeletePersonaOrItsConversation() = runBlocking {
        seedSharedPersona()
        database.personaDao().deletePersona("shared", "b")
        assertEquals(1, messages("a").size)
        assertEquals(1, messages("b").size)
    }

    @Test
    fun updatingPersonaDoesNotCascadeMessages() = runBlocking {
        seedSharedPersona()
        database.personaDao().insertCompletePersona(
            PersonaEntity("shared", "Updated", "", "", "", "a"), emptyList()
        )
        listOf("a", "b", "OFFLINE_USER").forEach { assertEquals(1, messages(it).size) }
    }

    @Test
    fun accountCleanupRejectsOfflineNamespace() = runBlocking {
        seedSharedPersona()
        val error = runCatching { database.personaDao().deleteAccountData("OFFLINE_USER") }.exceptionOrNull()
        org.junit.Assert.assertTrue(error is IllegalArgumentException)
        assertEquals(1, messages("OFFLINE_USER").size)
    }

    private suspend fun seedSharedPersona() {
        database.personaDao().insertPersona(PersonaEntity("shared", "Shared", "", "", "", "a"))
        listOf("a", "b", "OFFLINE_USER").forEach { owner ->
            database.messageDao().insertMessage(
                MessageEntity("message-$owner", "shared", owner, "original", true, 1L)
            )
        }
    }

    private suspend fun messages(owner: String) =
        database.messageDao().getRecentMessagesByPersonaId("shared", owner, 20)
}
