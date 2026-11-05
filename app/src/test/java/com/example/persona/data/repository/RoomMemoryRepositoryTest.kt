package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.core.auth.StoredSession
import com.example.persona.data.local.dao.MemoryDao
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.dto.AuthSessionDto
import com.example.persona.data.remote.dto.AuthUserDto
import com.example.persona.domain.model.MemoryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomMemoryRepositoryTest {
    private val dao: MemoryDao = mock()
    private val tokenStore: SessionTokenStore = mock()
    private val api: AuthApi = mock()
    private lateinit var auth: AuthManager
    private lateinit var repository: RoomMemoryRepository

    @Before
    fun setUp() {
        whenever(tokenStore.read()).thenReturn(StoredSession("token", "owner", "owner@test.dev"))
        auth = AuthManager(api, tokenStore, mock())
        repository = RoomMemoryRepository(dao, auth)
    }

    @Test
    fun `save binds owner and preserves explicit scope and permission`() = runTest {
        whenever(dao.saveMemory(any(), isNull())).thenAnswer { it.getArgument<MemoryEntity>(0) }
        val saved = repository.save("p", MemoryType.FACT, "manual fact", false)
        assertEquals("owner", saved.ownerId)
        assertEquals("p", saved.personaId)
        assertFalse(saved.cloudAllowed)
        assertNull(saved.sourceMessageId)
        assertEquals(1, saved.revision)
        assertEquals(1L, repository.changes.value)
        val shared = repository.save(null, MemoryType.GOAL, "explicit share", true)
        assertNull(shared.personaId)
        assertTrue(shared.cloudAllowed)
        assertNotEquals(saved.id, shared.id)
    }

    @Test
    fun `invalid content and missing update revision never reach storage`() = runTest {
        for (content in listOf("", "   ", "a".repeat(401))) {
            assertTrue(runCatching { repository.save("p", MemoryType.FACT, content, false) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            repository.save("p", MemoryType.FACT, "valid", false, id = "existing")
        }.exceptionOrNull() is IllegalArgumentException)
        verifyNoInteractions(dao)
        assertEquals(0L, repository.changes.value)
    }

    @Test
    fun `failed save and conflicting delete do not announce changes`() = runTest {
        whenever(dao.saveMemory(any(), eq(2))).thenThrow(IllegalStateException("conflict"))
        assertTrue(runCatching {
            repository.save("p", MemoryType.FACT, "new", false, id = "id", expectedRevision = 2)
        }.isFailure)
        whenever(dao.deleteMemory(eq("id"), eq("owner"), eq(2), any())).thenReturn(0)
        assertTrue(runCatching { repository.delete("id", 2) }.isFailure)
        assertEquals(0L, repository.changes.value)
    }

    @Test
    fun `delete and persona cleanup bind current owner and announce successful changes`() = runTest {
        whenever(dao.deleteMemory(eq("id"), eq("owner"), eq(3), any())).thenReturn(1)
        whenever(dao.deletePersonaMemories(eq("owner"), eq("p"), any())).thenReturn(2, 0)
        repository.delete("id", 3)
        repository.deleteForPersona("p")
        repository.deleteForPersona("p")
        assertEquals(2L, repository.changes.value)
        verify(dao).deleteMemory(eq("id"), eq("owner"), eq(3), any())
    }
    @Test
    fun `prompt re-queries and limits both count and content budget`() = runTest {
        val rows = (1..6).map { memory("$it", "x".repeat(400)) }
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(false), any()))
            .thenReturn(rows, listOf(memory("fresh", "edited")))
        val first = repository.selectForPrompt("p", false)
        assertEquals(listOf("1", "2", "3"), first.map { it.id })
        assertEquals(1200, first.sumOf { it.content.length })
        assertEquals("edited", repository.selectForPrompt("p", false).single().content)
        verify(dao, times(2)).selectForPrompt(eq("owner"), eq("p"), eq(false), any())
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(true), any()))
            .thenReturn((1..6).map { memory("$it", "short").copy(cloudAllowed = true) })
        assertEquals(4, repository.selectForPrompt("p", true).size)
    }

    @Test
    fun `prompt discards a row that expired while query was suspended`() = runTest {
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(false), any()))
            .thenReturn(listOf(memory("expired").copy(expiresAt = 1), memory("live")))
        assertEquals(listOf("live"), repository.selectForPrompt("p", false).map { it.id })
    }

    @Test
    fun `old observation cannot start after identity switches`() = runTest {
        val old = repository.observe("p")
        switchAccount()
        assertTrue(runCatching { old.first() }.exceptionOrNull() is CancellationException)
        verifyNoInteractions(dao)
    }

    @Test
    fun `observe passes owner and persona to storage`() = runTest {
        whenever(dao.observe(eq("owner"), eq("p"), any())).thenReturn(flowOf(listOf(memory("id"))))
        assertEquals("id", repository.observe("p").first().single().id)
        verify(dao).observe(eq("owner"), eq("p"), any())
    }

    @Test
    fun `stale noncancellable caller cannot write or clean up the next account`() = runTest {
        val old = auth.identity.value
        switchAccount()
        withContext(NonCancellable + old) {
            assertTrue(runCatching { repository.save("p", MemoryType.FACT, "late", false) }
                .exceptionOrNull() is CancellationException)
            assertTrue(runCatching { repository.deleteForPersona("p") }
                .exceptionOrNull() is CancellationException)
            assertTrue(runCatching { repository.delete("id", 1) }
                .exceptionOrNull() is CancellationException)
        }
        verifyNoInteractions(dao)
    }

    @Test
    fun `identity switch before query returns rejects its result`() = runTest {
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(false), any())).thenAnswer {
            auth.invalidateSession()
            listOf(memory("old-owner-result"))
        }
        assertTrue(runCatching { repository.selectForPrompt("p", false) }
            .exceptionOrNull() is CancellationException)
    }

    @Test
    fun `prompt and observation recheck owner scope deleted expiry and cloud permission`() = runTest {
        val rows = listOf(
            memory("foreign").copy(ownerId = "foreign"),
            memory("other-scope").copy(personaId = "other"),
            memory("deleted").copy(deleted = true),
            memory("expired").copy(expiresAt = 1),
            memory("private"),
            memory("shared").copy(personaId = null, cloudAllowed = true)
        )
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(true), any())).thenReturn(rows)
        assertEquals(listOf("shared"), repository.selectForPrompt("p", true).map { it.id })
        whenever(dao.observe(eq("owner"), eq("p"), any())).thenReturn(flowOf(rows))
        assertEquals(listOf("private", "shared"), repository.observe("p").first().map { it.id })
    }

    @Test
    fun `unknown memory type fails closed`() = runTest {
        val corrupt = memory("id").copy(type = "UNKNOWN", cloudAllowed = true)
        whenever(dao.selectForPrompt(eq("owner"), eq("p"), eq(true), any())).thenReturn(listOf(corrupt))
        assertTrue(runCatching { repository.selectForPrompt("p", true) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `cancelled mutations finish their commit and change publication together`() = runTest {
        for (operation in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            when (operation) {
                0 -> whenever(dao.saveMemory(any(), isNull())).doSuspendableAnswer { call ->
                    entered.complete(Unit)
                    release.await()
                    call.getArgument<MemoryEntity>(0)
                }
                1 -> whenever(dao.deleteMemory(eq("id"), eq("owner"), eq(1), any()))
                    .doSuspendableAnswer { entered.complete(Unit); release.await(); 1 }
                2 -> whenever(dao.deletePersonaMemories(eq("owner"), eq("p"), any()))
                    .doSuspendableAnswer { entered.complete(Unit); release.await(); 1 }
            }
            val writing = launch {
                when (operation) {
                    0 -> repository.save("p", MemoryType.FACT, "manual", false)
                    1 -> repository.delete("id", 1)
                    2 -> repository.deleteForPersona("p")
                }
            }
            entered.await()
            writing.cancel()
            release.complete(Unit)
            writing.join()
            assertEquals(operation.toLong() + 1, repository.changes.value)
        }
    }

    private fun memory(id: String, content: String = "fact") = MemoryEntity(
        id, "owner", "p", MemoryType.FACT.name, content, createdAt = 1, updatedAt = 2
    )

    private suspend fun switchAccount() {
        whenever(api.login(any())).thenReturn(
            AuthSessionDto("next-token", "2099-01-01", AuthUserDto("next", "next@test.dev"))
        )
        auth.signIn("next@test.dev", "password")
    }
}
