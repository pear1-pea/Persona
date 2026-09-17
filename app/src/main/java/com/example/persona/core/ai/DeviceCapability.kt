package com.example.persona.core.ai

data class DeviceProfile(
    val totalRamBytes: Long,
    val availableMemBytes: Long,
    val appMemoryClassBytes: Long,
    val nativePssBytes: Long,
    val freeStorageBytes: Long,
    val abi: String,
    val sdk: Int,
    val thermalStatus: ThermalStatus,
    val batteryPercent: Int,
    val powerSaveMode: Boolean
) {
    val ramGb: Int
        get() = totalRamBytes.toRoundedGb()

    val availableStorageBytes: Long
        get() = freeStorageBytes
}

typealias DeviceCapability = DeviceProfile

enum class ThermalStatus {
    UNKNOWN,
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN
}

enum class ModelAdmission {
    SUPPORTED,
    RISKY,
    BLOCKED
}

data class ModelAdmissionReport(
    val status: ModelAdmission,
    val reasons: List<String> = emptyList()
) {
    val isSelectable: Boolean
        get() = status != ModelAdmission.BLOCKED
}

internal fun evaluateModelAdmission(
    profile: DeviceProfile,
    requiredAbis: List<String>,
    minSdk: Int,
    minRamGb: Int,
    modelSizeBytes: Long,
    contextWindow: Int,
    reservedOutputTokens: Int,
    recentFailureCount: Int
): ModelAdmissionReport {
    val blockedReasons = mutableListOf<String>()
    val riskReasons = mutableListOf<String>()

    if (requiredAbis.isNotEmpty() && profile.abi !in requiredAbis) {
        blockedReasons += "当前 ABI ${profile.abi} 不在模型 requiredAbis 中"
    }
    if (profile.sdk < minSdk) {
        blockedReasons += "当前 Android SDK ${profile.sdk} 低于模型要求 $minSdk"
    }
    if (minRamGb > 0) {
        val minRamBytes = minRamGb * BYTES_PER_GB
        when {
            profile.totalRamBytes < minRamBytes * 7 / 10 ->
                blockedReasons += "设备总 RAM 明显低于模型建议 ${minRamGb}GB"
            profile.totalRamBytes < minRamBytes ->
                riskReasons += "设备总 RAM 低于模型建议 ${minRamGb}GB"
        }
        if (profile.availableMemBytes < minRamBytes / 5) {
            riskReasons += "当前可用内存偏低"
        }
    }
    if (profile.appMemoryClassBytes > 0 && modelSizeBytes > profile.appMemoryClassBytes * 4L) {
        riskReasons += "模型文件显著大于 App memory class"
    }
    if (profile.nativePssBytes > 0 && profile.availableMemBytes < profile.nativePssBytes / 2L) {
        riskReasons += "Native PSS 已接近当前可用内存"
    }
    if (profile.freeStorageBytes < MIN_FREE_STORAGE_BLOCK_BYTES) {
        blockedReasons += "模型目录可用存储不足"
    } else if (profile.freeStorageBytes < MIN_FREE_STORAGE_RISK_BYTES) {
        riskReasons += "模型目录可用存储偏低"
    }
    when (profile.thermalStatus) {
        ThermalStatus.CRITICAL,
        ThermalStatus.EMERGENCY,
        ThermalStatus.SHUTDOWN -> blockedReasons += "设备热状态过高"
        ThermalStatus.SEVERE -> riskReasons += "设备热状态偏高"
        else -> Unit
    }
    if (profile.powerSaveMode) {
        riskReasons += "设备处于省电模式"
    }
    if (profile.batteryPercent in 1 until 15) {
        riskReasons += "电量偏低"
    }
    if (recentFailureCount >= 2) {
        riskReasons += "近期本地加载失败次数较多，本次默认使用云端"
    } else if (recentFailureCount == 1) {
        riskReasons += "近期发生过本地加载失败，本次默认使用云端"
    }
    if (contextWindow > 4096 && profile.ramGb < 6) {
        riskReasons += "context window 较大，当前 RAM 可能不足"
    }
    if (reservedOutputTokens > contextWindow / 2) {
        riskReasons += "reserved output token 预算过高"
    }

    return when {
        blockedReasons.isNotEmpty() -> ModelAdmissionReport(ModelAdmission.BLOCKED, blockedReasons + riskReasons)
        riskReasons.isNotEmpty() -> ModelAdmissionReport(ModelAdmission.RISKY, riskReasons)
        else -> ModelAdmissionReport(ModelAdmission.SUPPORTED)
    }
}

private fun Long.toRoundedGb(): Int {
    if (this <= 0L) return 0
    return ((this + BYTES_PER_GB - 1) / BYTES_PER_GB).toInt()
}

private const val BYTES_PER_GB = 1_000_000_000L
private const val MIN_FREE_STORAGE_BLOCK_BYTES = 100L * 1024L * 1024L
private const val MIN_FREE_STORAGE_RISK_BYTES = 512L * 1024L * 1024L
