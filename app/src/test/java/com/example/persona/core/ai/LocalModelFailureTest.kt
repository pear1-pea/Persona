package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class LocalModelFailureTest {

    @Test
    fun `transient failures use bounded exponential backoff`() {
        val first = LocalModelFailure(1, 1_000L, LocalModelFailureType.TRANSIENT)
        val third = LocalModelFailure(3, 1_000L, LocalModelFailureType.TRANSIENT)

        assertEquals(15_000L, first.retryDelayMillis)
        assertEquals(60_000L, third.retryDelayMillis)
        assertEquals(16_000L, first.retryAtMillis())
    }

    @Test
    fun `out of memory and thermal failures have separate delays`() {
        val oom = LocalModelFailure(1, 0L, LocalModelFailureType.OUT_OF_MEMORY)
        val thermal = LocalModelFailure(1, 0L, LocalModelFailureType.THERMAL)

        assertEquals(30_000L, oom.retryDelayMillis)
        assertEquals(60_000L, thermal.retryDelayMillis)
    }

    @Test
    fun `permanent failures are never retried automatically`() {
        val corrupt = LocalModelFailure(1, 0L, LocalModelFailureType.CORRUPT)
        val incompatible = LocalModelFailure(1, 0L, LocalModelFailureType.INCOMPATIBLE)

        assertTrue(corrupt.isPermanent)
        assertTrue(incompatible.isPermanent)
        assertEquals(Long.MAX_VALUE, corrupt.retryAtMillis())
        assertFalse(corrupt.isReadyToRetry(Long.MAX_VALUE - 1))
        assertFalse(incompatible.isReadyToRetry(System.currentTimeMillis()))
    }

    @Test
    fun `user cancellation is never a blocking failure`() {
        val cancelled = LocalModelFailure(3, 1_000L, LocalModelFailureType.USER_CANCELLED)

        assertEquals(0L, cancelled.retryDelayMillis)
        assertTrue(cancelled.isReadyToRetry(1_000L))
        assertFalse(cancelled.isPermanent)
    }

    @Test
    fun `failure becomes retryable after its backoff window`() {
        val failure = LocalModelFailure(2, 10_000L, LocalModelFailureType.OUT_OF_MEMORY)

        assertFalse(failure.isReadyToRetry(10_000L + 59_999L))
        assertTrue(failure.isReadyToRetry(10_000L + 60_000L))
    }

    @Test
    fun `non permanent records expire strictly after the statistics window`() {
        val windowMillis = 24L * 60L * 60L * 1_000L
        val types = listOf(
            LocalModelFailureType.OUT_OF_MEMORY,
            LocalModelFailureType.THERMAL,
            LocalModelFailureType.USER_CANCELLED,
            LocalModelFailureType.TRANSIENT,
            LocalModelFailureType.UNKNOWN
        )

        for (type in types) {
            val failure = LocalModelFailure(1, 1_000L, type)

            assertFalse(type.name, failure.isExpired(1_000L + windowMillis - 1L, windowMillis))
            assertFalse(type.name, failure.isExpired(1_000L + windowMillis, windowMillis))
            assertTrue(type.name, failure.isExpired(1_000L + windowMillis + 1L, windowMillis))
        }
    }

    @Test
    fun `permanent records survive the statistics window and maximum timestamp`() {
        val windowMillis = 24L * 60L * 60L * 1_000L
        for (type in listOf(LocalModelFailureType.CORRUPT, LocalModelFailureType.INCOMPATIBLE)) {
            val failure = LocalModelFailure(1, 1_000L, type)

            assertFalse(type.name, failure.isExpired(1_000L + windowMillis + 1L, windowMillis))
            assertFalse(type.name, failure.isExpired(Long.MAX_VALUE, windowMillis))
            assertFalse(type.name, failure.isExpired(Long.MAX_VALUE, 0L))
            assertFalse(type.name, failure.isReadyToRetry(Long.MAX_VALUE))
        }
    }

    @Test
    fun `retry eligibility does not expire a transient record`() {
        val failure = LocalModelFailure(1, 1_000L, LocalModelFailureType.TRANSIENT)

        assertTrue(failure.isReadyToRetry(16_000L))
        assertFalse(failure.isExpired(16_000L, 60_000L))
        assertTrue(failure.isExpired(61_001L, 60_000L))
    }

    @Test
    fun `zero window expires a record only after its timestamp`() {
        val failure = LocalModelFailure(1, 1_000L, LocalModelFailureType.TRANSIENT)

        assertFalse(failure.isExpired(999L, 0L))
        assertFalse(failure.isExpired(1_000L, 0L))
        assertTrue(failure.isExpired(1_001L, 0L))
    }

    @Test
    fun `clock rollback does not expire a future failure`() {
        val failure = LocalModelFailure(1, 100_000L, LocalModelFailureType.UNKNOWN)

        assertFalse(failure.isExpired(1_000L, 60_000L))
        assertFalse(failure.isExpired(Long.MIN_VALUE, 60_000L))
    }

    @Test
    fun `expiry arithmetic does not wrap at long boundaries`() {
        val earliest = LocalModelFailure(1, Long.MIN_VALUE, LocalModelFailureType.TRANSIENT)
        val latest = LocalModelFailure(1, Long.MAX_VALUE - 1L, LocalModelFailureType.TRANSIENT)

        assertFalse(earliest.isExpired(-1L, Long.MAX_VALUE))
        assertTrue(earliest.isExpired(0L, Long.MAX_VALUE))
        assertTrue(earliest.isExpired(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(latest.isExpired(Long.MAX_VALUE, 0L))
        assertFalse(latest.isExpired(Long.MAX_VALUE, 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative expiry windows are rejected`() {
        LocalModelFailure(1, 1_000L, LocalModelFailureType.TRANSIENT)
            .isExpired(2_000L, -1L)
    }

    @Test
    fun `cancellation classification takes precedence over diagnostic keywords`() {
        for (message in listOf(null, "cancelled", "config tokenizer memory alloc symbol ABI")) {
            assertEquals(
                LocalModelFailureType.USER_CANCELLED,
                classifyLocalModelFailure(CancellationException(message))
            )
        }
        assertEquals(
            LocalModelFailureType.USER_CANCELLED,
            classifyLocalModelFailure(object : CancellationException("config") {})
        )
    }

    @Test
    fun `ambiguous native load failure does not permanently mark a model corrupt`() {
        assertEquals(LocalModelFailureType.TRANSIENT,
            classifyLocalModelFailure(IllegalStateException("MNN generation configuration/load failed")))
    }

    @Test
    fun `non cancellation failures retain their classification`() {
        val cases = listOf(
            null to LocalModelFailureType.UNKNOWN,
            OutOfMemoryError("config") to LocalModelFailureType.OUT_OF_MEMORY,
            UnsatisfiedLinkError("memory") to LocalModelFailureType.INCOMPATIBLE,
            java.io.FileNotFoundException("memory") to LocalModelFailureType.CORRUPT,
            SecurityException("ABI") to LocalModelFailureType.CORRUPT,
            IllegalStateException("TOKENIZER missing") to LocalModelFailureType.CORRUPT,
            IllegalStateException("allocation failed") to LocalModelFailureType.OUT_OF_MEMORY,
            IllegalStateException("ABI mismatch") to LocalModelFailureType.INCOMPATIBLE,
            IllegalStateException("cancelled by peer") to LocalModelFailureType.TRANSIENT,
            IllegalStateException() to LocalModelFailureType.TRANSIENT
        )

        for ((error, expectedType) in cases) {
            assertEquals(expectedType, classifyLocalModelFailure(error))
        }
    }
}
