package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel

interface PromptAdapter {
    fun canHandle(model: InstalledModel): Boolean

    fun build(
        model: InstalledModel,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): NativePromptPayload
}
