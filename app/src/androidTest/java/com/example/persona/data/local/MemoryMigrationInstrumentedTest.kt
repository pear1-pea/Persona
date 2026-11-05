package com.example.persona.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.entity.MemoryEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryMigrationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun v7MigrationValidatesWithRoomPreservesMessagesAndInstallsSourceTrigger() = runBlocking {
        val name = "memory-migration-${UUID.randomUUID()}"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
                createV7(legacy)
                legacy.execSQL("INSERT INTO personas VALUES ('p', 'fixture', '', '', '', 'owner', 1)")
                legacy.execSQL("""
                    INSERT INTO messages(id, personaId, ownerId, content, isFromUser, timestamp, status)
                    VALUES ('source', 'p', 'owner', 'synthetic source', 1, 1, 'NORMAL')
                """.trimIndent())
                legacy.version = 7
            }
            // No callback: the migration itself must install the integrity trigger.
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9).build()
            try {
                val sql = db.openHelper.writableDatabase
                val message = db.messageDao().getRecentMessagesByPersonaId("p", "owner", 1).single()
                assertEquals("synthetic source", message.content)
                assertEquals("NORMAL", message.status)
                sql.query("SELECT localOnly, excludeFromHistory FROM messages WHERE id = 'source'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                    assertEquals(1, cursor.getInt(1))
                }
                sql.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'tombstone_memories_before_message_delete'")
                    .use { assertTrue(it.moveToFirst()) }
                val dao = db.memoryDao()
                dao.saveMemory(MemoryEntity(
                    "memory", "owner", "p", "FACT", "manual fact", "source", 1, 1
                ), null)
                sql.execSQL("DELETE FROM messages WHERE id = 'source'")
                val deleted = requireNotNull(dao.findOwned("memory", "owner"))
                assertTrue(deleted.deleted)
                assertEquals("", deleted.content)
                assertNull(deleted.sourceMessageId)
                assertEquals(2, deleted.revision)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createV7(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE personas (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, avatarUrl TEXT NOT NULL,
                postImageUrl TEXT NOT NULL, backstory TEXT NOT NULL, creatorId TEXT NOT NULL,
                isPublic INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE traits (
                traitId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personaId TEXT NOT NULL,
                traitContent TEXT NOT NULL,
                FOREIGN KEY(personaId) REFERENCES personas(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_traits_personaId ON traits(personaId)")
        db.execSQL("""
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY, personaId TEXT NOT NULL,
                ownerId TEXT NOT NULL DEFAULT 'OFFLINE_USER', content TEXT NOT NULL,
                isFromUser INTEGER NOT NULL, timestamp INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'NORMAL',
                FOREIGN KEY(personaId) REFERENCES personas(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_messages_personaId_ownerId ON messages(personaId, ownerId)")
    }
}
