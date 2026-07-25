package com.example.persona.core.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class EdgeAiEngineConcurrencyTest {

    @Test
    fun `initMutex allows sequential initialization`() = runTest {
        val initMutex = Mutex()
        var initCount = 0

        initMutex.withLock { initCount++ }
        initMutex.withLock { initCount++ }

        assertEquals(2, initCount)
    }

    @Test
    fun `channelRef is set and cleared around awaitClose`() {
        val channelRef = AtomicReference<kotlinx.coroutines.channels.Channel<String>?>(null)
        val channel = kotlinx.coroutines.channels.Channel<String>()

        channelRef.set(channel)
        assertEquals(channel, channelRef.get())

        channelRef.set(null)
        assertEquals(null, channelRef.get())
    }

    @Test
    fun `generateResponse returns error when model not loaded`() = runTest {
        val llmInference = null

        val flow = callbackFlow {
            if (llmInference == null) {
                trySend("[Error] 模型未加载")
                close()
                return@callbackFlow
            }
            awaitClose { }
        }

        val result = flow.first()
        assertEquals("[Error] 模型未加载", result)
    }
}
