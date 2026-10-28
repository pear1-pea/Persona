package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage

class ContextBudgetExceeded(message: String) : IllegalArgumentException(message)

object CloudContextPlanner {
    fun plan(system: String, user: String, history: List<ChatMessage>): List<ChatMessage> {
        if (system.length > MAX_MESSAGE_CHARS || user.length > MAX_MESSAGE_CHARS ||
            system.length + user.length > MAX_INPUT_CHARS
        ) throw ContextBudgetExceeded("系统设定或当前消息超出云端预算，请缩短后重试")
        val result = mutableListOf(ChatMessage("system", system), ChatMessage("user", user))
        val conversation = history.filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }
        var index = conversation.lastIndex
        while (index >= 0) {
            val end = conversation[index]
            if (end.role != "assistant" || index == 0 || conversation[index - 1].role != "user") {
                index--
                continue
            }
            val turn = listOf(conversation[index - 1], end)
            if (turn.any { it.content.length > MAX_MESSAGE_CHARS } || result.size + 2 > MAX_MESSAGES ||
                result.sumOf { it.content.length } + turn.sumOf { it.content.length } > MAX_INPUT_CHARS
            ) break
            result.addAll(1, turn)
            index -= 2
        }
        return result
    }

    private const val MAX_MESSAGE_CHARS = 12_000
    private const val MAX_MESSAGES = 24
    private const val MAX_INPUT_CHARS = 24_000
}
