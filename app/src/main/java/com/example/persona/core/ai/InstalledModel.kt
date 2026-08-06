package com.example.persona.core.ai

data class InstalledModel(
    val id: String,
    val name: String,
    val version: String,
    val modelDir: String,
    val backend: Backend
)
