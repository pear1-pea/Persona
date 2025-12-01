package com.example.persona.features.creation

import com.google.gson.annotations.SerializedName

// parse AI-generated JSON results
data class GeneratedPersonaDto(
    @SerializedName("name") val name: String,
    @SerializedName("backstory") val backstory: String,
    @SerializedName("traits") val traits: List<String>
)