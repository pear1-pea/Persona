package com.example.persona.core.memory

import com.example.persona.core.ai.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryExtractionRunnerTest {
    private val source = "我喜欢茶"
    private val valid = """{"suggestions":[{"type":"PREFERENCE","content":"我喜欢茶"}]}"""
    private val allowed = QualityUpgradePermission(true, true, false, NetworkState.UNMETERED)

    @Test fun `one contract failure upgrades using fresh cloud input and reports both contracts`() = runBlocking {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        val validations = mutableListOf<Pair<Boolean, Boolean>>()
        val result = MemoryExtractionRunner.run(source, false, { allowed }, { cloud, automatic ->
            calls += cloud to automatic
            if (cloud) valid else "invalid json"
        }, { cloud, accepted -> validations += cloud to accepted })
        assertEquals(listOf(false to false, true to true), calls)
        assertEquals(listOf(false to false, true to true), validations)
        assertTrue(result.upgraded)
        assertTrue(result.usedCloud)
        assertEquals(source, result.suggestions.single().content)
    }

    @Test fun `each independent restriction prevents escalation`() = runBlocking {
        val permissions = listOf(allowed.copy(requested = false), allowed.copy(cloudAuthorized = false),
            allowed.copy(localOnly = true), allowed.copy(stopped = true)) +
            NetworkState.entries.filter { it != NetworkState.UNMETERED }.map { allowed.copy(network = it) }
        for (permission in permissions) {
            var calls = 0
            val failure = runCatching {
                MemoryExtractionRunner.run(source, false, { permission }, { cloud, _ ->
                    calls++
                    assertFalse(cloud)
                    "invalid"
                })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, calls)
            assertNotEquals(QualityUpgradeReason.ALLOWED, QualityUpgradePolicy.decide(permission))
        }
    }

    @Test fun `empty valid output does not upgrade or request permission`() = runBlocking {
        val result = MemoryExtractionRunner.run(source, false, { error("Must not check upgrade permission") },
            { cloud, automatic -> assertFalse(cloud); assertFalse(automatic); """{"suggestions":[]}""" })
        assertTrue(result.suggestions.isEmpty())
        assertFalse(result.upgraded)
    }

    @Test fun `valid but semantically wrong source substring is not called semantic success`() = runBlocking {
        val fiction = "假设我喜欢茶"
        val result = MemoryExtractionRunner.run(fiction, false, { error("No contract failure") }, { _, _ -> valid })
        assertEquals(source, result.suggestions.single().content)
        assertFalse(result.upgraded)
    }

    @Test fun `runtime error and cancellation never become contract fallback`() = runBlocking {
        for (error in listOf(IllegalStateException("runtime"), CancellationException("cancelled"))) {
            var calls = 0
            val observed = runCatching {
                MemoryExtractionRunner.run(source, false, { error("Must not authorize") }, { _, _ -> calls++; throw error })
            }.exceptionOrNull()
            assertSame(error, observed)
            assertEquals(1, calls)
        }
    }

    @Test fun `cloud rejection ends after second call`() = runBlocking {
        var calls = 0
        val failure = runCatching {
            MemoryExtractionRunner.run(source, false, { allowed }, { _, _ -> calls++; "invalid" })
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(2, calls)
    }

    @Test fun `direct cloud rejection is not retried`() = runBlocking {
        var calls = 0
        assertTrue(runCatching {
            MemoryExtractionRunner.run(source, true, { error("No escalation from cloud") }, { cloud, automatic ->
                calls++; assertTrue(cloud); assertFalse(automatic); "invalid"
            })
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, calls)
    }

    @Test fun `revoked source authorization aborts before second dispatch`() = runBlocking {
        val revoked = CancellationException("source changed")
        var calls = 0
        val observed = runCatching {
            MemoryExtractionRunner.run(source, false, { throw revoked }, { _, _ -> calls++; "invalid" })
        }.exceptionOrNull()
        assertSame(revoked, observed)
        assertEquals(1, calls)
    }

    @Test fun `invalid source is refused before any inference`() = runBlocking {
        for (input in listOf("", "x".repeat(4001), "\uD800")) {
            var calls = 0
            assertTrue(runCatching {
                MemoryExtractionRunner.run(input, false, { allowed }, { _, _ -> calls++; valid })
            }.isFailure)
            assertEquals(0, calls)
        }
    }
}
