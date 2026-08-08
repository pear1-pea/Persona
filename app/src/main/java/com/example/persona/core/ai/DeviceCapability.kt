package com.example.persona.core.ai

data class DeviceCapability(
    val ramGb: Int,
    val abi: String,
    val sdk: Int,
    val availableStorageBytes: Long
)
