package com.example.persona.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.di.DatabaseModule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterruptedMessageRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "persona-p2-recovery-${UUID.randomUUID()}"
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        database = open()
        database.personaDao().insertPersona(PersonaEntity("fixture", "Fixture", "", "", "", "a"))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun reopenPreservesPartialContentAndOtherTerminalStates() = runBlocking {
        insert("partial-a", "a", "partial response", "GENERATING")
        insert("partial-b", "b", "another response", "GENERATING")
        insert("normal", "a", "complete", "NORMAL")
        insert("failed", "a", "error", "FAILED")
        insert("stopped", "OFFLINE_USER", "stopped", "STOPPED")
        database.close()
        database = open()
        assertEquals("partial response" to "STOPPED", message("a", "partial-a"))
        assertEquals("another response" to "STOPPED", message("b", "partial-b"))
        assertEquals("complete" to "NORMAL", message("a", "normal"))
        assertEquals("error" to "FAILED", message("a", "failed"))
        assertEquals("stopped" to "STOPPED", message("OFFLINE_USER", "stopped"))
    }

    @Test
    fun unfinishedThinkingPlaceholderIsRecoveredIdempotently() = runBlocking {
        insert("thinking", "a", "正在思考...", "GENERATING")
        repeat(2) {
            database.close()
            database = open()
            assertEquals("已停止生成" to "STOPPED", message("a", "thinking"))
        }
    }

    @Test
    fun readingAnOpenDatabaseDoesNotStopAnActiveGeneration() = runBlocking {
        insert("live", "a", "active", "GENERATING")
        repeat(2) { assertEquals("active" to "GENERATING", message("a", "live")) }
        database.close()
        database = open()
        assertEquals("active" to "STOPPED", message("a", "live"))
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                DatabaseModule.recoverInterruptedMessages(db)
            }
        }).build()

    private suspend fun insert(id: String, owner: String, content: String, status: String) {
        database.messageDao().insertMessage(MessageEntity(id, "fixture", owner, content, false, 1L, status))
    }

    private suspend fun message(owner: String, id: String): Pair<String, String> =
        database.messageDao().getRecentMessagesByPersonaId("fixture", owner, 10)
            .single { it.id == id }.let { it.content to it.status }
}
