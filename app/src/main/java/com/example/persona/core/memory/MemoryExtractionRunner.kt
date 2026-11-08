package com.example.persona.core.memory

import com.example.persona.core.ai.NetworkState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class QualityUpgradePermission(
    val requested: Boolean,
    val cloudAuthorized: Boolean,
    val localOnly: Boolean,
    val network: NetworkState,
    val stopped: Boolean = false
)

enum class QualityUpgradeReason { ALLOWED, DISABLED, NO_CONSENT, LOCAL_ONLY, NETWORK_BLOCKED, STOPPED }

object QualityUpgradePolicy {
    fun decide(permission: QualityUpgradePermission): QualityUpgradeReason = when {
        permission.stopped -> QualityUpgradeReason.STOPPED
        !permission.requested -> QualityUpgradeReason.DISABLED
        !permission.cloudAuthorized -> QualityUpgradeReason.NO_CONSENT
        permission.localOnly -> QualityUpgradeReason.LOCAL_ONLY
        permission.network != NetworkState.UNMETERED -> QualityUpgradeReason.NETWORK_BLOCKED
        else -> QualityUpgradeReason.ALLOWED
    }
}

data class ExtractionRun(
    val suggestions: List<MemoryExtraction.Suggestion>,
    val usedCloud: Boolean,
    val upgraded: Boolean
)

object MemoryExtractionRunner {
    suspend fun run(
        source: String,
        startInCloud: Boolean,
        permission: suspend () -> QualityUpgradePermission,
        generate: suspend (cloud: Boolean, automatic: Boolean) -> String,
        contract: suspend (cloud: Boolean, accepted: Boolean) -> Unit = { _, _ -> }
    ): ExtractionRun {
        require(source.isNotBlank())
        MemoryExtraction.prompt(source)
        var cloud = startInCloud
        var upgraded = false
        while (true) {
            currentCoroutineContext().ensureActive()
            // Generation errors and cancellation are outside the contract-rejection catch.
            val output = generate(cloud, upgraded)
            val suggestions = try {
                MemoryExtraction.decode(output, source)
            } catch (invalid: IllegalArgumentException) {
                contract(cloud, false)
                currentCoroutineContext().ensureActive()
                if (cloud || upgraded || QualityUpgradePolicy.decide(permission()) != QualityUpgradeReason.ALLOWED) {
                    throw invalid
                }
                cloud = true
                upgraded = true
                continue
            }
            contract(cloud, true)
            return ExtractionRun(suggestions, cloud, upgraded)
        }
    }
}
