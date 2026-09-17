package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel

internal object PromptHistoryTrimmer {
    fun trimForApproximateContext(
        model: InstalledModel,
        history: List<ChatMessage>,
        prompt: String,
        params: GenerationParams,
        tokenizer: Tokenizer = ConservativeTokenizer
    ): List<ChatMessage> {
        return ContextPlanner.plan(
            model = model,
            history = history,
            prompt = prompt,
            params = params,
            tokenizer = tokenizer
        )
    }
}

internal const val ROLE_SYSTEM = "system"
internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"

internal fun normalizeRole(role: String): String {
    return when (role.trim().lowercase()) {
        ROLE_SYSTEM -> ROLE_SYSTEM
        ROLE_ASSISTANT -> ROLE_ASSISTANT
        else -> ROLE_USER
    }
}
