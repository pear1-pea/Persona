package com.example.persona.data.remote

data class BackendConfig(
    val baseUrl: String,
    val modelId: String,
    val isConfigured: Boolean = true
)
