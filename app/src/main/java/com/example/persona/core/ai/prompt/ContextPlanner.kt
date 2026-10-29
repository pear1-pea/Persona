package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel

internal object ContextPlanner {
    fun plan(
        model: InstalledModel,
        history: List<ChatMessage>,
        prompt: String,
        params: GenerationParams,
        tokenizer: Tokenizer,
        renderForCount: ((List<ChatMessage>) -> String)? = null,
        templateSpecialTokens: Int = DEFAULT_TEMPLATE_SPECIAL_TOKENS,
        safetyMargin: Int = DEFAULT_SAFETY_MARGIN
    ): List<ChatMessage> {
        val maxInputTokens = (
            model.contextWindow - effectiveReservedOutputTokens(params) -
                templateSpecialTokens - safetyMargin
            )
        if (maxInputTokens < MIN_INPUT_TOKENS) throw ContextBudgetExceeded("模型上下文不足以保留输出预算")
        val systemMessage = history.firstOrNull { normalizeRole(it.role) == ROLE_SYSTEM }
            ?.takeIf { it.content.isNotBlank() }
        val conversation = history
            .filter { normalizeRole(it.role) != ROLE_SYSTEM && it.content.isNotBlank() }
        val currentUserMessage = ChatMessage(role = ROLE_USER, content = prompt.trim())

        val messages = mutableListOf<ChatMessage>()
        systemMessage?.let(messages::add)
        messages.addAll(selectRecentHistory(conversation, systemMessage, currentUserMessage, maxInputTokens, tokenizer))
        messages.add(currentUserMessage)

        return fitToBudget(
            initialMessages = messages,
            maxInputTokens = maxInputTokens,
            tokenizer = tokenizer,
            renderForCount = renderForCount
        )
    }

    internal fun renderForPlanning(messages: List<ChatMessage>): String {
        return buildString {
            messages.forEach { message ->
                append("<|im_start|>")
                append(normalizeRole(message.role))
                append('\n')
                append(message.content.trim())
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    private fun selectRecentHistory(
        conversation: List<ChatMessage>,
        systemMessage: ChatMessage?,
        currentUserMessage: ChatMessage,
        maxInputTokens: Int,
        tokenizer: Tokenizer
    ): List<ChatMessage> {
        val selectedGroups = mutableListOf<List<ChatMessage>>()
        val groups = conversation.toRecentGroups()
        var usedTokens = systemMessage?.estimatedTokens(tokenizer) ?: 0
        usedTokens += currentUserMessage.estimatedTokens(tokenizer)

        for (group in groups) {
            val groupTokens = group.sumOf { it.estimatedTokens(tokenizer) }
            if (usedTokens + groupTokens <= maxInputTokens) {
                selectedGroups.add(0, group)
                usedTokens += groupTokens
            } else {
                break
            }
        }
        return selectedGroups.flatten()
    }

    private fun fitToBudget(
        initialMessages: List<ChatMessage>,
        maxInputTokens: Int,
        tokenizer: Tokenizer,
        renderForCount: ((List<ChatMessage>) -> String)?
    ): List<ChatMessage> {
        val messages = initialMessages.toMutableList()
        fun count(current: List<ChatMessage>): Int {
            return renderForCount?.invoke(current)?.let(tokenizer::countTokens)
                ?: current.sumOf { it.estimatedTokens(tokenizer) }
        }

        var previousCount = Int.MAX_VALUE
        while (true) {
            val currentCount = count(messages)
            if (currentCount <= maxInputTokens) break
            if (currentCount >= previousCount) break
            previousCount = currentCount
            val historyStart = if (messages.firstOrNull()?.let { normalizeRole(it.role) } == ROLE_SYSTEM) 1 else 0
            val currentIndex = messages.lastIndex
            if (currentIndex > historyStart) {
                val removeIndex = historyStart
                val removeCount = if (
                    removeIndex + 1 < currentIndex &&
                    normalizeRole(messages[removeIndex].role) == ROLE_USER &&
                    normalizeRole(messages[removeIndex + 1].role) == ROLE_ASSISTANT
                ) 2 else 1
                repeat(removeCount) { messages.removeAt(removeIndex) }
                continue
            }

            val currentIndexAfterHistory = messages.lastIndex
            if (currentIndexAfterHistory >= 0) throw ContextBudgetExceeded("系统设定或当前消息超出本地上下文预算，请缩短后重试")
        }
        if (count(messages) > maxInputTokens) throw ContextBudgetExceeded("无法在模型预算内保留当前输入与系统规则")
        return messages
    }

    private fun List<ChatMessage>.toRecentGroups(): List<List<ChatMessage>> {
        val groups = mutableListOf<List<ChatMessage>>()
        var index = lastIndex
        while (index >= 0) {
            val current = this[index]
            if (
                normalizeRole(current.role) == ROLE_ASSISTANT &&
                index > 0 &&
                normalizeRole(this[index - 1].role) == ROLE_USER
            ) {
                groups += listOf(this[index - 1], current)
                index -= 2
            } else {
                groups += listOf(current)
                index--
            }
        }
        return groups
    }

    private fun ChatMessage.estimatedTokens(tokenizer: Tokenizer): Int {
        return tokenizer.countTokens(content.trim()) + MESSAGE_TOKEN_OVERHEAD
    }

    private fun effectiveReservedOutputTokens(params: GenerationParams): Int {
        return if (params.maxTokens > 0) params.maxTokens else DEFAULT_NATIVE_MAX_TOKENS
    }

    private const val MESSAGE_TOKEN_OVERHEAD = 4
    private const val DEFAULT_TEMPLATE_SPECIAL_TOKENS = 24
    private const val DEFAULT_SAFETY_MARGIN = 32
    private const val MIN_INPUT_TOKENS = 1
    private const val DEFAULT_NATIVE_MAX_TOKENS = 512
}
