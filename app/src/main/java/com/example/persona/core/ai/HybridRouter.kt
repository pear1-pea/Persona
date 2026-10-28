package com.example.persona.core.ai

import javax.inject.Inject
import javax.inject.Singleton

enum class PrivacyLevel {
    PUBLIC,
    NORMAL,
    PRIVATE,
    SENSITIVE
}

enum class TaskComplexity {
    SIMPLE,
    NORMAL,
    COMPLEX
}

enum class NetworkState {
    OFFLINE,
    METERED,
    UNMETERED,
    UNKNOWN
}

enum class Route {
    LOCAL,
    CLOUD,
    LOCAL_THEN_CLOUD,
    CLOUD_ONLY
}

data class RoutingInput(
    val forceCloud: Boolean,
    val localOnly: Boolean,
    val localAdmission: ModelAdmission,
    val privacyLevel: PrivacyLevel,
    val taskComplexity: TaskComplexity,
    val networkState: NetworkState,
    val batteryPercent: Int,
    val thermalStatus: ThermalStatus,
    val powerSaveMode: Boolean,
    val localRetryBlocked: Boolean,
    val localEnabled: Boolean = true
)

data class RoutingDecision(
    val route: Route,
    val reasons: List<String>,
    val isExecutable: Boolean = true,
    val cloudAllowed: Boolean = route != Route.LOCAL
) {
    val isLocalPreferred: Boolean
        get() = route == Route.LOCAL || route == Route.LOCAL_THEN_CLOUD
}

@Singleton
class HybridRouter @Inject constructor() {
    fun decide(input: RoutingInput): RoutingDecision {
        val thermalHigh = input.thermalStatus in setOf(
            ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN
        )
        val batteryLow = input.batteryPercent in 0..15
        val privateRequest = input.localOnly || input.privacyLevel in setOf(PrivacyLevel.PRIVATE, PrivacyLevel.SENSITIVE)
        val cloudAllowed = !privateRequest && input.networkState != NetworkState.OFFLINE &&
            (input.networkState != NetworkState.METERED || input.forceCloud)
        val unavailable = buildList {
            if (!input.localEnabled) add("本地推理已禁用")
            if (input.localAdmission == ModelAdmission.BLOCKED) add("本地模型准入状态为 BLOCKED")
            if (input.localRetryBlocked) add("本地模型仍在失败退避期或需要修复")
            if (thermalHigh) add("设备热状态偏高")
            if (input.localAdmission == ModelAdmission.RISKY) {
                if (batteryLow) add("当前电量偏低")
                if (input.powerSaveMode) add("设备处于省电模式")
            }
        }
        val constraints = buildList {
            if (privateRequest) add("仅本地或隐私约束禁止云端上传：${input.privacyLevel}")
            if (input.networkState == NetworkState.OFFLINE) add("当前网络离线")
            if (input.networkState == NetworkState.METERED && !input.forceCloud) add("当前网络按流量计费，禁止自动使用云端")
        }
        if (!cloudAllowed) {
            return RoutingDecision(Route.LOCAL, constraints + unavailable,
                isExecutable = unavailable.isEmpty(), cloudAllowed = false)
        }
        if (input.forceCloud) return RoutingDecision(Route.CLOUD_ONLY, listOf("用户明确选择云端，隐私与网络约束允许"))
        if (unavailable.isNotEmpty()) return RoutingDecision(Route.CLOUD_ONLY, unavailable)

        if (input.taskComplexity == TaskComplexity.COMPLEX) {
            return RoutingDecision(
                Route.CLOUD,
                listOf("任务复杂度为 COMPLEX，优先使用云端能力")
            )
        }

        return RoutingDecision(
            Route.LOCAL_THEN_CLOUD,
            listOf("常规任务，本地优先并允许云端兜底")
        )
    }
}
