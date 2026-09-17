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
        params: GenerationParams,
        tokenizer: Tokenizer
    ): NativePromptPayload {
        val messages = ContextPlanner.plan(
            model = model,
            history = history,
            prompt = prompt,
            params = params,
            tokenizer = tokenizer,
            renderForCount = ::render
        )
        return NativePromptPayload.RawText(text = render(messages))
    }

    private fun render(messages: List<ChatMessage>): String {
        return ContextPlanner.renderForPlanning(messages)
    }
}
