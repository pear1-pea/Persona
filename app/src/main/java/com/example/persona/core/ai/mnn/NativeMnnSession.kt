package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.prompt.NativePromptPayload
import java.util.concurrent.locks.ReentrantReadWriteLock

internal fun interface NativeTokenCallback {
    fun onToken(token: String): Boolean
}

internal data class NativeGenerationDiagnostics(
    val samplerRevision: Int,
    val maxTokens: Int,
    val decodeSteps: Int,
    val outputTokens: Int
)

data class NativeGenerationTimings(
    val inputTokens: Long,
    val outputTokens: Long,
    val prefillMicros: Long,
    val decodeMicros: Long,
    val samplingMicros: Long
)

interface NativeMnnRuntimeSession {
    fun timings(): NativeGenerationTimings? = null
    fun load(modelConfigPath: String): Boolean

    fun countTokens(text: String): Int

    fun reset()

    fun generate(
        payload: NativePromptPayload,
        params: GenerationParams,
        onToken: (String) -> Boolean
    )

    fun stop()

    fun close()
}

fun interface NativeSessionFactory {
    fun create(): NativeMnnRuntimeSession
}

internal class NativeMnnSession : NativeMnnRuntimeSession {
    private val lifecycleLock = ReentrantReadWriteLock()

    @Volatile
    private var handle = 0L

    override fun timings(): NativeGenerationTimings? {
        lifecycleLock.readLock().lock()
        try {
            if (handle == 0L) return null
            val values = nativeTimings(handle) ?: return null
            check(values.size == 5)
            return NativeGenerationTimings(values[0], values[1], values[2], values[3], values[4])
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun load(modelConfigPath: String): Boolean {
        ensureLibraryLoaded()
        lifecycleLock.writeLock().lock()
        try {
            check(handle == 0L) { "MNN session is already loaded" }
            handle = nativeCreate(modelConfigPath)
            return handle != 0L
        } finally {
            lifecycleLock.writeLock().unlock()
        }
    }

    override fun countTokens(text: String): Int {
        lifecycleLock.readLock().lock()
        try {
            val nativeHandle = handle
            check(nativeHandle != 0L) { "MNN session has not been loaded" }
            return nativeCountTokens(nativeHandle, text).coerceAtLeast(0)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun reset() {
        lifecycleLock.readLock().lock()
        try {
            val nativeHandle = handle
            check(nativeHandle != 0L) { "MNN session has not been loaded" }
            nativeReset(nativeHandle)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun generate(
        payload: NativePromptPayload,
        params: GenerationParams,
        onToken: (String) -> Boolean
    ) {
        lifecycleLock.readLock().lock()
        try {
            val nativeHandle = handle
            check(nativeHandle != 0L) { "MNN session has not been loaded" }
            when (payload) {
                is NativePromptPayload.ChatMessages -> nativeGenerateChatMessages(
                    handle = nativeHandle,
                    roles = payload.messages.map { it.role }.toTypedArray(),
                    contents = payload.messages.map { it.content }.toTypedArray(),
                    stopWords = payload.stopWords.toTypedArray(),
                    temperature = params.temperature,
                    topP = params.topP,
                    maxTokens = params.maxTokens,
                    callback = NativeTokenCallback(onToken)
                )

                is NativePromptPayload.RawText -> nativeGenerateRawText(
                    handle = nativeHandle,
                    promptText = payload.text,
                    stopWords = payload.stopWords.toTypedArray(),
                    temperature = params.temperature,
                    topP = params.topP,
                    maxTokens = params.maxTokens,
                    callback = NativeTokenCallback(onToken)
                )
            }
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun stop() {
        lifecycleLock.readLock().lock()
        try {
            if (handle != 0L) nativeStop(handle)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    // This advances the active sampler RNG; use only in isolated runtime tests.
    internal fun sampleForVerification(draws: Int = 4096): IntArray {
        require(draws in 1..65536)
        lifecycleLock.readLock().lock()
        try {
            check(handle != 0L) { "MNN session has not been loaded" }
            return checkNotNull(nativeSampleForVerification(handle, draws)) {
                "MNN active sampler verification failed"
            }
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    internal fun generationDiagnostics(): NativeGenerationDiagnostics {
        lifecycleLock.readLock().lock()
        try {
            check(handle != 0L) { "MNN session has not been loaded" }
            val values = checkNotNull(nativeGenerationDiagnostics(handle)) {
                "MNN generation diagnostics unavailable"
            }
            check(values.size == 4)
            return NativeGenerationDiagnostics(values[0], values[1], values[2], values[3])
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun close() {
        lifecycleLock.readLock().lock()
        try {
            if (handle != 0L) nativeStop(handle)
        } finally {
            lifecycleLock.readLock().unlock()
        }

        lifecycleLock.writeLock().lock()
        try {
            val nativeHandle = handle
            if (nativeHandle != 0L) {
                nativeDestroy(nativeHandle)
                handle = 0L
            }
        } finally {
            lifecycleLock.writeLock().unlock()
        }
    }

    private fun ensureLibraryLoaded() {
        check(libraryLoadError == null) {
            "MNN native runtime is unavailable: ${libraryLoadError?.message}"
        }
    }

    private external fun nativeCreate(modelConfigPath: String): Long

    private external fun nativeGenerateRawText(
        handle: Long,
        promptText: String,
        stopWords: Array<String>,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        callback: NativeTokenCallback
    )

    private external fun nativeCountTokens(handle: Long, text: String): Int

    private external fun nativeReset(handle: Long)

    private external fun nativeGenerateChatMessages(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        stopWords: Array<String>,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        callback: NativeTokenCallback
    )

    private external fun nativeStop(handle: Long)

    private external fun nativeSampleForVerification(handle: Long, draws: Int): IntArray?

    private external fun nativeGenerationDiagnostics(handle: Long): IntArray?
    private external fun nativeTimings(handle: Long): LongArray?

    private external fun nativeDestroy(handle: Long)

    private companion object {
        private val libraryLoadError: Throwable? = runCatching {
            System.loadLibrary("persona_mnn")
        }.exceptionOrNull()
    }
}
