package com.example.persona.core.ai

data class LocalModelManifest(
    val schemaVersion: Int? = null,
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
    val minSdk: Int? = null,
    val requiredAbis: List<String>? = null,
    val files: List<LocalModelManifestFile>? = null,
    val signature: String? = null
)

data class LocalModelManifestFile(
    val path: String? = null,
    val role: String? = null,
    val size: Long? = null,
    val sha256: String? = null
)
