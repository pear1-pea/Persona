package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.prompt.NativePromptPayload

internal fun interface NativeTokenCallback {
    fun onToken(token: String): Boolean
}

internal class NativeMnnSession {
    private val handleLock = Any()
    private val generationCloseLock = Any()

    @Volatile
    private var handle = 0L

    fun load(modelConfigPath: String): Boolean {
        ensureLibraryLoaded()
        val nativeHandle = nativeCreate(modelConfigPath)
        synchronized(handleLock) {
            handle = nativeHandle
        }
        return nativeHandle != 0L
    }

    fun generate(
        payload: NativePromptPayload,
        params: GenerationParams,
        onToken: (String) -> Boolean
    ) {
        synchronized(generationCloseLock) {
            val nativeHandle = synchronized(handleLock) { handle }
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
        }
    }

    fun stop() {
        synchronized(handleLock) {
            if (handle != 0L) nativeStop(handle)
        }
    }

    fun close() {
        val nativeHandle = synchronized(handleLock) {
            val current = handle
            if (current != 0L) {
                nativeStop(current)
                handle = 0L
            }
            current
        }
        if (nativeHandle == 0L) return

        synchronized(generationCloseLock) {
            nativeDestroy(nativeHandle)
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

    private external fun nativeDestroy(handle: Long)

    private companion object {
        private val libraryLoadError: Throwable? = runCatching {
            System.loadLibrary("persona_mnn")
        }.exceptionOrNull()
    }
}
