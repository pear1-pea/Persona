package com.example.persona.core.ai.mnn

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class Qwen3ProbeFailure(val errorType: String) : RuntimeException(errorType)

internal class Qwen3ProbeWorker(private val session: NativeMnnSession) {
    var usable = true
        private set

    fun <T> call(seconds: Long = 60, action: () -> T): T {
        check(usable)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "qwen3-native-research").apply { isDaemon = true }
        }
        val future = executor.submit(Callable { action() })
        try {
            return future.get(seconds, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            usable = false
            stopBounded()
            throw Qwen3ProbeFailure("TimeoutException")
        } catch (_: InterruptedException) {
            usable = false
            stopBounded()
            throw Qwen3ProbeFailure("InterruptedException")
        } catch (error: ExecutionException) {
            throw Qwen3ProbeFailure(error.cause?.javaClass?.simpleName ?: "ExecutionException")
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) usable = false
        }
    }

    private fun stopBounded() {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "qwen3-native-stop").apply { isDaemon = true }
        }
        val future = executor.submit { session.stop() }
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The session stays poisoned; never race reset/close with a stuck JNI call.
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
