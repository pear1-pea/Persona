package com.example.persona.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model") val model: String, 
    @SerializedName("messages") val messages: List<MessageDto>,
    @SerializedName("stream") val stream: Boolean = true
)

data class MessageDto(
    @SerializedName("role") val role: String, // "user", "assistant", "system"
    @SerializedName("content") val content: String
)