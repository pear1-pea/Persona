package com.example.persona.core.ai.prompt

sealed interface NativePromptPayload {
    val stopWords: List<String>

    data class ChatMessages(
        val messages: List<NativeMessage>,
        override val stopWords: List<String> = listOf(STOP_EOP)
    ) : NativePromptPayload

    data class RawText(
        val text: String,
        override val stopWords: List<String> = listOf(STOP_EOP, STOP_QWEN_IM_END)
    ) : NativePromptPayload

    companion object {
        const val STOP_EOP = "<eop>"
        const val STOP_QWEN_IM_END = "<|im_end|>"
    }
}
