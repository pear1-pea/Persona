package com.example.persona.core.ai

sealed interface ModelScanResult {
    data class Ready(
        val model: InstalledModel,
        val admission: ModelAdmissionReport
    ) : ModelScanResult
    data object NotInstalled : ModelScanResult
    data class Corrupted(val reason: String) : ModelScanResult
    data class Unsupported(val reason: String) : ModelScanResult
    data class Failed(val reason: String) : ModelScanResult
}
