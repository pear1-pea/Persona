package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams

internal fun interface NativeTokenCallback {
    fun onToken(token: String): Boolean
}

internal class NativeMnnSession {
    private var handle = 0L

    fun load(modelConfigPath: String): Boolean {
        ensureLibraryLoaded()
        handle = nativeCreate(modelConfigPath)
        return handle != 0L
    }

    fun generate(
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams,
        onToken: (String) -> Boolean
    ) {
        check(handle != 0L) { "MNN session has not been loaded" }
        nativeGenerate(
            handle = handle,
            prompt = prompt,
            historyRoles = history.map { it.role }.toTypedArray(),
            historyContents = history.map { it.content }.toTypedArray(),
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens,
            callback = NativeTokenCallback(onToken)
        )
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun ensureLibraryLoaded() {
        check(libraryLoadError == null) {
            "MNN native runtime is unavailable: ${libraryLoadError?.message}"
        }
    }

    private external fun nativeCreate(modelConfigPath: String): Long

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        historyRoles: Array<String>,
        historyContents: Array<String>,
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