package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel

internal object PromptHistoryTrimmer {
    fun trimForApproximateContext(
        model: InstalledModel,
        history: List<ChatMessage>,
        prompt: String,
        params: GenerationParams
    ): List<ChatMessage> {
        val systemMessage = history.firstOrNull { normalizeRole(it.role) == ROLE_SYSTEM }
        val conversation = history
            .filterNot { it === systemMessage }
            .filter { it.content.isNotBlank() } +
            ChatMessage(role = ROLE_USER, content = prompt)

        val maxPromptChars = maxOf(
            MIN_PROMPT_CHARS,
            (model.contextWindow - params.maxTokens).coerceAtLeast(512) * APPROX_CHARS_PER_TOKEN
        )
        val selected = ArrayDeque<ChatMessage>()
        var usedChars = systemMessage?.content?.length ?: 0

        conversation.asReversed().forEach { message ->
            val messageSize = message.content.length + MESSAGE_OVERHEAD_CHARS
            if (selected.isNotEmpty() && usedChars + messageSize > maxPromptChars) {
                return@forEach
            }
            selected.addFirst(message)
            usedChars += messageSize
        }

        return listOfNotNull(systemMessage?.takeIf { it.content.isNotBlank() }) + selected
    }

    private const val APPROX_CHARS_PER_TOKEN = 4
    private const val MESSAGE_OVERHEAD_CHARS = 32
    private const val MIN_PROMPT_CHARS = 1000
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
