package com.example.persona.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.persona.data.local.entity.*
import com.example.persona.di.DatabaseModule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryCurationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "p3-curation-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private val dao get() = db.memoryCurationDao()

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        db.personaDao().insertPersona(PersonaEntity("p", "fixture", "", "", "", "owner"))
        db.messageDao().insertMessage(MessageEntity("s", "p", "owner", "我喜欢简短回答", true, 1))
        dao.configure(MemoryPreferencesEntity("owner", true, false))
        dao.insert(candidate("c"))
    }

    @After fun cleanup() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun candidateSurvivesReopenAndDuplicateInsertionIsIdempotent() = runBlocking {
        assertEquals(-1L, dao.insert(candidate("c")))
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        assertEquals("PENDING", dao.candidate("owner", "c")?.status)
        assertNull(dao.candidate("foreign", "c"))
        assertFalse(requireNotNull(dao.preferences("owner")).cloudExtractionAllowed)
    }

    @Test fun messageRevisionInvalidatesCandidateAndScrubsContent() = runBlocking {
        db.messageDao().updateMessageContent("s", "owner", "修改后的原文", "NORMAL")
        assertEquals("INVALID", dao.candidate("owner", "c")?.status)
        assertEquals("", dao.candidate("owner", "c")?.content)
    }

    @Test fun ignoredCandidateNeedsAnExplicitReopenAndCannotBeAcceptedTwice() = runBlocking {
        assertEquals(1, dao.finish("owner", "c", "IGNORED"))
        assertEquals(0, dao.finish("owner", "c", "ACCEPTED", "memory"))
        assertEquals(0, dao.reopen("foreign", "c", "forbidden"))
        assertEquals(1, dao.reopen("owner", "c", "我喜欢简短回答"))
        assertEquals(1, dao.finish("owner", "c", "ACCEPTED", "memory"))
        assertEquals(0, dao.finish("owner", "c", "ACCEPTED", "memory"))
    }

    @Test fun deletingAnySummarySourceClearsSummaryAndAllItsReferences() = runBlocking {
        db.messageDao().insertMessage(MessageEntity("second", "p", "owner", "我正在学习 Kotlin", true, 2))
        db.memoryDao().insert(MemoryEntity("summary", "owner", "p", "FACT", "合成摘要", createdAt = 1, updatedAt = 1))
        dao.addSummarySources(listOf(
            MemorySummarySourceEntity("summary", "s", "owner", "hash"),
            MemorySummarySourceEntity("summary", "second", "owner", "hash")
        ))
        db.messageDao().deleteMessagesByPersonaId("p", "owner")
        assertTrue(requireNotNull(db.memoryDao().findOwned("summary", "owner")).deleted)
        assertTrue(dao.summarySources("owner", "summary").isEmpty())
    }

    @Test fun accountCleanupRemovesOnlyTheOwnersCandidatesAndPreferences() = runBlocking {
        dao.insert(candidate("foreign").copy(ownerId = "foreign"))
        dao.configure(MemoryPreferencesEntity("foreign", true, true))
        db.personaDao().deleteAccountData("owner")
        assertNull(dao.candidate("owner", "c"))
        assertNull(dao.preferences("owner"))
        assertNotNull(dao.candidate("foreign", "foreign"))
        assertNotNull(dao.preferences("foreign"))
    }

    @Test fun editingSummaryRetainsItAndSourcesWhileExcludingOldHistory() = runBlocking {
        db.messageDao().insertMessage(MessageEntity("second", "p", "owner", "我正在学习 Kotlin", true, 2))
        val original = MemoryEntity("summary-edit", "owner", "p", "FACT", "原始摘要", createdAt = 1, updatedAt = 1)
        val sources = listOf(
            MemorySummarySourceEntity(original.id, "s", "owner", "first-hash"),
            MemorySummarySourceEntity(original.id, "second", "owner", "second-hash")
        )
        db.memoryDao().insert(original)
        dao.addSummarySources(sources)
        db.memoryDao().insert(original.copy(id = "summary-other"))
        dao.addSummarySources(sources.map { it.copy(memoryId = "summary-other") })

        val edited = db.memoryDao().saveMemory(original.copy(content = "用户编辑的摘要", updatedAt = 3), 1)

        assertEquals("用户编辑的摘要", edited.content)
        assertFalse(edited.deleted)
        assertFalse(edited.cloudAllowed)
        assertEquals(2, edited.revision)
        assertEquals(sources.sortedBy { it.sourceMessageId }, dao.summarySources("owner", original.id))
        for (id in listOf("s", "second")) {
            assertTrue(requireNotNull(db.messageDao().getMessageById(id, "owner")).excludeFromHistory)
        }
        assertTrue(requireNotNull(db.memoryDao().findOwned("summary-other", "owner")).deleted)

        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(DatabaseModule.memoryIntegrityCallback).build()
        assertEquals(edited, db.memoryDao().findOwned(original.id, "owner"))
        assertEquals(sources.sortedBy { it.sourceMessageId }, dao.summarySources("owner", original.id))
        db.messageDao().updateMessageContent("second", "owner", "修改后的来源", "NORMAL")
        assertTrue(requireNotNull(db.memoryDao().findOwned(original.id, "owner")).deleted)
        assertTrue(dao.summarySources("owner", original.id).isEmpty())
    }

    @Test fun staleSummaryEditLeavesContentSourcesAndHistoryUntouched() = runBlocking {
        val original = MemoryEntity("summary-stale", "owner", "p", "FACT", "原始摘要", createdAt = 1, updatedAt = 1)
        val sources = listOf(MemorySummarySourceEntity(original.id, "s", "owner", "hash"))
        db.memoryDao().insert(original)
        dao.addSummarySources(sources)

        assertTrue(runCatching {
            db.memoryDao().saveMemory(original.copy(content = "过期编辑"), 2)
        }.isFailure)

        assertEquals(original, db.memoryDao().findOwned(original.id, "owner"))
        assertEquals(sources, dao.summarySources("owner", original.id))
        assertFalse(requireNotNull(db.messageDao().getMessageById("s", "owner")).excludeFromHistory)
    }

    private fun candidate(id: String) = MemoryCandidateEntity(id, "owner", "p", "s", "hash",
        "我喜欢简短回答", "PREFERENCE", "style", "rule-v1", true, "PENDING", null, 1)
}
