package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptAdapterRegistry @Inject constructor() {
    private val adapters = listOf(
        QwenChatMlTextAdapter,
        MnnChatMessagesAdapter
    )

    fun buildPayload(
        model: InstalledModel,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): NativePromptPayload {
        val adapter = adapters.firstOrNull { it.canHandle(model) }
            ?: error("No prompt adapter found for family=${model.family}, promptFormat=${model.promptFormat}")
        return adapter.build(model, prompt, history, params)
    }
}
