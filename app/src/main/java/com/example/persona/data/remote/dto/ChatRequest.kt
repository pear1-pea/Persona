package com.example.persona.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<MessageDto>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("top_p") val topP: Float = 0.8f,
    @SerializedName("thinking") val thinking: ThinkingDto = ThinkingDto()
)

data class MessageDto(
    @SerializedName("role") val role: String, // "user", "assistant", "system"
    @SerializedName("content") val content: String
)

data class ThinkingDto(
    @SerializedName("type") val type: String = "disabled"
)
