package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.PromptFormats

object MnnChatMessagesAdapter : PromptAdapter {
    override fun canHandle(model: InstalledModel): Boolean {
        return model.promptFormat == PromptFormats.MNN_CHAT_MESSAGES
    }

    override fun build(
        model: InstalledModel,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): NativePromptPayload {
        val messages = PromptHistoryTrimmer.trimForApproximateContext(
            model = model,
            history = history,
            prompt = prompt,
            params = params
        ).map { message ->
            NativeMessage(
                role = normalizeRole(message.role),
                content = message.content.trim()
            )
        }
        return NativePromptPayload.ChatMessages(messages = messages)
    }
}
