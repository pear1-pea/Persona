package com.example.persona.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.di.DatabaseModule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "memory-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private val dao get() = db.memoryDao()

    @Before
    fun setUp() = runBlocking {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        for (id in listOf("p", "other")) {
            db.personaDao().insertPersona(PersonaEntity(id, id, "", "", "", "owner"))
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun sqlFiltersOwnerScopeExpiryCloudAndTombstonesWithStableOrdering() = runBlocking {
        listOf(
            memory("b"), memory("a"), memory("shared").copy(personaId = null, cloudAllowed = true),
            memory("foreign").copy(ownerId = "other-owner"), memory("other").copy(personaId = "other"),
            memory("expired").copy(expiresAt = 10), memory("deleted").copy(deleted = true)
        ).forEach { dao.insert(it) }
        assertEquals(listOf("a", "b", "shared"), dao.selectForPrompt("owner", "p", false, 10).map { it.id })
        assertEquals(listOf("shared"), dao.selectForPrompt("owner", "p", true, 10).map { it.id })
    }

    @Test
    fun promptCandidatesIncludeOldRowsBeyondThePreviousFourRowLimit() = runBlocking {
        for (index in 1..600) dao.insert(memory("recent-$index").copy(updatedAt = 100))
        dao.insert(memory("old-relevant").copy(content = "我不吃花生", updatedAt = 1))
        val rows = dao.selectForPrompt("owner", "p", false, 10)
        assertEquals(601, rows.size)
        assertEquals("old-relevant", rows.last().id)
    }

    @Test
    fun saveAndDeleteUseRevisionCasAndNeverReplaceAnotherOwnersId() = runBlocking {
        dao.saveMemory(memory("id"), null)
        val edited = dao.saveMemory(memory("id").copy(content = "edited", createdAt = 999, updatedAt = 3), 1)
        assertEquals(1L, edited.createdAt)
        assertEquals(2, edited.revision)
        assertTrue(runCatching { dao.saveMemory(memory("id"), 1) }.isFailure)
        assertTrue(runCatching { dao.saveMemory(memory("id").copy(ownerId = "attacker"), 2) }.isFailure)
        assertTrue(runCatching { dao.saveMemory(memory("id").copy(ownerId = "attacker"), null) }.isFailure)
        assertEquals(0, dao.deleteMemory("id", "attacker", 2, 4))
        assertEquals(0, dao.deleteMemory("id", "owner", 1, 4))
        assertEquals(1, dao.deleteMemory("id", "owner", 2, 4))
        val deleted = requireNotNull(dao.findOwned("id", "owner"))
        assertEquals("", deleted.content)
        assertNull(deleted.sourceMessageId)
        assertEquals(3, deleted.revision)
        assertTrue(deleted.deleted)
        assertTrue(runCatching { dao.saveMemory(memory("id"), 3) }.isFailure)
    }

    @Test
    fun sourceRequiresMatchingOwnerPersonaAndNormalUserAndExcludesWholeScope() = runBlocking {
        message("valid")
        message("assistant", fromUser = false)
        message("failed", status = "FAILED")
        message("streaming", status = "GENERATING")
        message("foreign", owner = "foreign")
        message("other", persona = "other")
        for (id in listOf("assistant", "failed", "streaming", "foreign", "other", "missing")) {
            assertTrue(runCatching {
                dao.saveMemory(memory("rejected-$id").copy(sourceMessageId = id), null)
            }.isFailure)
            assertNull(dao.findOwned("rejected-$id", "owner"))
        }
        assertFalse(excluded("assistant"))
        dao.saveMemory(memory("valid-memory").copy(sourceMessageId = "valid"), null)
        for (id in listOf("valid", "assistant", "failed", "streaming")) assertTrue(excluded(id))
        assertFalse(excluded("foreign"))
        assertFalse(excluded("other"))
        // An explicit account share keeps the source's real persona for validation.
        dao.saveMemory(memory("shared").copy(personaId = null, sourceMessageId = "other"), null)
        assertTrue(excluded("other"))
        assertFalse(excluded("foreign"))
    }

    @Test
    fun sourceDeletionOnFreshDatabaseTombstonesAndCannotBeSavedAgain() = runBlocking {
        message("source")
        dao.saveMemory(memory("id").copy(sourceMessageId = "source"), null)
        dao.insert(memory("foreign").copy(ownerId = "foreign", sourceMessageId = "source"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id = 'source'")
        val deleted = requireNotNull(dao.findOwned("id", "owner"))
        assertTrue(deleted.deleted)
        assertEquals("", deleted.content)
        assertNull(deleted.sourceMessageId)
        assertEquals(2, deleted.revision)
        assertFalse(requireNotNull(dao.findOwned("foreign", "foreign")).deleted)
        assertTrue(runCatching { dao.saveMemory(memory("id"), 2) }.isFailure)
        assertTrue(runCatching {
            dao.saveMemory(memory("new").copy(sourceMessageId = "source"), null)
        }.isFailure)
    }

    @Test
    fun failedCasLeavesHistoryUntouchedAndScopeMoveExcludesBothScopes() = runBlocking {
        dao.saveMemory(memory("id"), null)
        message("old-scope")
        message("new-scope", persona = "other")
        message("foreign", owner = "foreign")
        assertTrue(runCatching {
            dao.saveMemory(memory("id").copy(personaId = "other"), 99)
        }.isFailure)
        assertEquals(0, dao.deleteMemory("id", "owner", 99, 3))
        assertFalse(excluded("old-scope"))
        assertFalse(excluded("new-scope"))
        dao.saveMemory(memory("id").copy(personaId = "other"), 1)
        assertTrue(excluded("old-scope"))
        assertTrue(excluded("new-scope"))
        assertFalse(excluded("foreign"))
    }

    private suspend fun message(
        id: String, owner: String = "owner", persona: String = "p",
        fromUser: Boolean = true, status: String = "NORMAL"
    ) {
        db.messageDao().insertMessage(MessageEntity(id, persona, owner, "synthetic history", fromUser, 1, status))
    }

    @Test
    fun deletePersonaTombstonesOnlyItsOwnerAndScopeAndExcludesHistory() = runBlocking {
        dao.insert(memory("p"))
        dao.insert(memory("other").copy(personaId = "other"))
        dao.insert(memory("shared").copy(personaId = null))
        dao.insert(memory("foreign").copy(ownerId = "foreign"))
        message("p-history")
        message("other-history", persona = "other")
        message("foreign-history", owner = "foreign")
        assertEquals(1, dao.deletePersonaMemories("owner", "p", 5))
        val deleted = requireNotNull(dao.findOwned("p", "owner"))
        assertTrue(deleted.deleted)
        assertEquals("", deleted.content)
        assertEquals(2, deleted.revision)
        assertEquals(listOf("shared"), dao.selectForPrompt("owner", "p", false, 6).map { it.id })
        assertFalse(requireNotNull(dao.findOwned("other", "owner")).deleted)
        assertFalse(requireNotNull(dao.findOwned("foreign", "foreign")).deleted)
        assertTrue(excluded("p-history"))
        assertFalse(excluded("other-history"))
        assertFalse(excluded("foreign-history"))
        assertEquals(0, dao.deletePersonaMemories("owner", "p", 6))
    }

    @Test
    fun deletingAccountScopedMemoryExcludesAllOwnerHistory() = runBlocking {
        dao.insert(memory("shared").copy(personaId = null))
        message("p-history")
        message("other-history", persona = "other")
        message("foreign-history", owner = "foreign")
        assertEquals(1, dao.deleteMemory("shared", "owner", 1, 3))
        assertTrue(excluded("p-history"))
        assertTrue(excluded("other-history"))
        assertFalse(excluded("foreign-history"))
    }

    @Test
    fun accountDeletionPurgesOnlyItsOwnersMemories() = runBlocking {
        dao.insert(memory("own"))
        dao.insert(memory("shared").copy(personaId = null))
        dao.insert(memory("foreign").copy(ownerId = "foreign"))
        dao.insert(memory("offline").copy(ownerId = "OFFLINE_USER"))
        db.personaDao().deleteAccountData("owner")
        assertNull(dao.findOwned("own", "owner"))
        assertNull(dao.findOwned("shared", "owner"))
        assertEquals("manual fact", dao.findOwned("foreign", "foreign")?.content)
        assertEquals("manual fact", dao.findOwned("offline", "OFFLINE_USER")?.content)
    }

    @Test
    fun historyUpdateFailureRollsBackSaveDeleteAndPersonaDeletion() = runBlocking {
        dao.insert(memory("existing"))
        message("history")
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_history_fixture BEFORE UPDATE OF excludeFromHistory ON messages
            BEGIN SELECT RAISE(ABORT, 'synthetic history failure'); END
        """.trimIndent())
        assertTrue(runCatching { dao.saveMemory(memory("new"), null) }.isFailure)
        assertNull(dao.findOwned("new", "owner"))
        assertTrue(runCatching { dao.saveMemory(memory("existing").copy(content = "edit"), 1) }.isFailure)
        assertTrue(runCatching { dao.deleteMemory("existing", "owner", 1, 3) }.isFailure)
        assertTrue(runCatching { dao.deletePersonaMemories("owner", "p", 3) }.isFailure)
        assertEquals(memory("existing"), dao.findOwned("existing", "owner"))
        assertFalse(excluded("history"))
    }

    private fun excluded(id: String): Boolean = db.openHelper.writableDatabase.query(
        "SELECT excludeFromHistory FROM messages WHERE id = ?", arrayOf(id)
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0) == 1
    }

    private fun memory(id: String) = MemoryEntity(
        id, "owner", "p", "FACT", "manual fact", createdAt = 1, updatedAt = 2
    )
}
