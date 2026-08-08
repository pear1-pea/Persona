package com.example.persona.core.ai

data class LocalModelManifest(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val backend: String? = null,
    val family: String? = null,
    val promptFormat: String? = null,
    val entry: String? = null,
    val tokenizer: String? = null,
    val contextWindow: Int? = null,
    val minRamGb: Int? = null,
    val minSdk: Int? = null
)
