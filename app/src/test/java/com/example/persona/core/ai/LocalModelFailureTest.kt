package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
