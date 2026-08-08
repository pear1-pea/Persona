package com.example.persona.core.ai.prompt

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAdapterRegistryTest {
    private val registry = PromptAdapterRegistry()

    @Test
    fun `qwen chatml model renders raw text`() {
        val payload = registry.buildPayload(
            model = sampleModel(PromptFormats.QWEN_CHATML_TEXT),
            prompt = "你好",
            history = listOf(
                ChatMessage("system", "你是 Persona"),
                ChatMessage("assistant", "旧回复")
            ),
            params = GenerationParams()
        )

        assertTrue(payload is NativePromptPayload.RawText)
        val text = (payload as NativePromptPayload.RawText).text
        assertTrue(text.contains("<|im_start|>system\n你是 Persona<|im_end|>"))
        assertTrue(text.contains("<|im_start|>assistant\n旧回复<|im_end|>"))
        assertTrue(text.contains("<|im_start|>user\n你好<|im_end|>"))
        assertTrue(text.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `mnn chat messages format preserves plain role content pairs`() {
        val payload = registry.buildPayload(
            model = sampleModel(promptFormat = PromptFormats.MNN_CHAT_MESSAGES),
            prompt = "继续",
            history = listOf(ChatMessage("system", "系统提示")),
            params = GenerationParams()
        )

        assertTrue(payload is NativePromptPayload.ChatMessages)
        val messages = (payload as NativePromptPayload.ChatMessages).messages
        assertEquals(
            listOf(
                NativeMessage("system", "系统提示"),
                NativeMessage("user", "继续")
            ),
            messages
        )
        assertFalse(messages.any { it.content.contains("<|im_start|>") })
    }

    private fun sampleModel(promptFormat: String) = InstalledModel(
        id = "qwen2.5-0.5b-instruct-mnn",
        name = "Qwen2.5 0.5B Instruct",
        version = "local",
        modelDir = "/models/qwen2.5-0.5b-instruct-mnn",
        backend = Backend.MNN,
        family = ModelFamilies.QWEN2_5,
        promptFormat = promptFormat,
        contextWindow = 4096
    )
}
