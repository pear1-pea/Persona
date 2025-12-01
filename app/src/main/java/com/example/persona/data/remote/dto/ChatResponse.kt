package com.example.persona.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    @SerializedName("choices") val choices: List<ChoiceDto>
)

data class ChoiceDto(
    @SerializedName("delta") val delta: DeltaDto? 
)

data class DeltaDto(
    @SerializedName("content") val content: String? 
)