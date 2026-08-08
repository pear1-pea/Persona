package com.example.persona.core.ai

data class InstalledModel(
    val id: String,
    val name: String,
    val version: String,
    val modelDir: String,
    val backend: Backend,
    val family: String,
    val promptFormat: String,
    val entry: String = "",
    val tokenizer: String = "",
    val contextWindow: Int,
    val minRamGb: Int = 0,
    val minSdk: Int = 0,
    val manifestPath: String = ""
)
