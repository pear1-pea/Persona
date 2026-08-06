package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.data.remote.DeepSeekApi
import com.example.persona.data.remote.DeepSeekConfig
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.remote.dto.ChatResponse
import com.example.persona.data.remote.dto.MessageDto
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class CloudChatRepository @Inject constructor(
    private val api: DeepSeekApi,
    private val config: DeepSeekConfig
) {
    fun streamResponse(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList()
    ): Flow<String> = flow {
        if (config.apiKey.isBlank()) {
            emit(DEEPSEEK_NOT_CONFIGURED_MESSAGE)
            return@flow
        }

        val messages = buildMessages(systemPrompt, userMessage, history)
        val request = ChatRequest(
            model = config.modelId.ifBlank { DEFAULT_DEEPSEEK_MODEL },
            messages = messages
        )

        val response = try {
            api.streamChat(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek network error", e)
            emit(DEEPSEEK_NETWORK_ERROR_MESSAGE)
            return@flow
        }

        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string().orEmpty()
            Log.e(TAG, "DeepSeek error ${response.code()}: $errorBody")
            emit("Error: DeepSeek 云端请求失败(${response.code()})，请检查 API Key、模型名或余额。")
            return@flow
        }

        val source = response.body()?.byteStream()?.bufferedReader() ?: return@flow
        val gson = Gson()

        try {
            var line: String? = source.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    val jsonStr = line.substring(5).trim()

                    if (jsonStr == "[DONE]") break

                    runCatching {
                        val chatResponse = gson.fromJson(jsonStr, ChatResponse::class.java)
                        chatResponse.choices.firstOrNull()?.delta?.content
                    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { content ->
                        emit(content)
                    }
                }
                line = source.readLine()
            }
        } finally {
            source.close()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generatePersonaProfile(keywords: String): String {
        val systemPrompt = """
            You are a creative character designer.
            Task: Create a unique persona based on user keywords.

            IMPORTANT: You must return ONLY a raw JSON object. Do not wrap it in markdown code blocks (like ```json).
            The JSON structure must be:
            {
              "name": "Character Name",
              "backstory": "A short, engaging biography (under 100 words).",
              "traits": ["Trait1", "Trait2", "Trait3"]
            }
        """.trimIndent()

        val userPrompt = if (keywords.isBlank()) "Theme: Sci-Fi, Mysterious" else "Keywords: $keywords"
        val fullResponseBuilder = StringBuilder()

        streamResponse(systemPrompt, userPrompt).collect { token ->
            fullResponseBuilder.append(token)
        }

        return fullResponseBuilder.toString()
    }

    private fun buildMessages(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage>
    ): List<MessageDto> {
        return buildList {
            add(MessageDto("system", systemPrompt))
            history
                .filter { it.content.isNotBlank() && it.role != "system" }
                .takeLast(CLOUD_HISTORY_LIMIT)
                .forEach { message ->
                    add(
                        MessageDto(
                            role = when (message.role) {
                                "assistant" -> "assistant"
                                else -> "user"
                            },
                            content = message.content
                        )
                    )
                }
            add(MessageDto("user", userMessage))
        }
    }

    private companion object {
        const val TAG = "CloudChatRepository"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val CLOUD_HISTORY_LIMIT = 12
        const val DEEPSEEK_NOT_CONFIGURED_MESSAGE = "Error: DeepSeek 未配置 API Key，请在 local.properties 填写 DEEPSEEK_API_KEY。"
        const val DEEPSEEK_NETWORK_ERROR_MESSAGE = "Error: DeepSeek 云端连接失败，请稍后再试。"
    }
}
