package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceAdmissionTest {
    @Test
    fun `insufficient available memory blocks a load even on a high RAM device`() {
        val profile = DeviceProfile(
            totalRamBytes = 12_000_000_000L, availableMemBytes = 32_000_000L,
            appMemoryClassBytes = 512_000_000L, nativePssBytes = 0L,
            freeStorageBytes = 10_000_000_000L, abi = "arm64-v8a", sdk = 31,
            thermalStatus = ThermalStatus.NOMINAL, batteryPercent = 80, powerSaveMode = false
        )
        assertEquals(ModelAdmission.BLOCKED, evaluateModelAdmission(profile,
            listOf("arm64-v8a"), 24, 4, 550_000_000L, 4096, 512, 0).status)
    }
}
