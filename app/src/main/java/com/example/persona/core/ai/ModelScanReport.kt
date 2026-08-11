package com.example.persona.core.ai

data class ModelScanReport(
    val directoryName: String,
    val modelDir: String,
    val result: ModelScanResult,
    val totalSizeBytes: Long,
    val scannedAtMillis: Long,
    val manifestRaw: String?
)
