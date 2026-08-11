package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats

object QwenChatMlTextAdapter : PromptAdapter {
    override fun canHandle(model: InstalledModel): Boolean {
        return model.family == ModelFamilies.QWEN2_5 &&
            model.promptFormat == PromptFormats.QWEN_CHATML_TEXT
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
        )
        val text = buildString {
            messages.forEach { message ->
                append("<|im_start|>")
                append(normalizeRole(message.role))
                append('\n')
                append(message.content.trim())
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
        return NativePromptPayload.RawText(text = text)
    }
}
