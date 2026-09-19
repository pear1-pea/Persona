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
    val latencyBudgetMs: Long?,
    val recentLocalFailureRate: Float
)

data class RoutingDecision(
    val route: Route,
    val reasons: List<String>
) {
    val isLocalPreferred: Boolean
        get() = route == Route.LOCAL || route == Route.LOCAL_THEN_CLOUD
}

@Singleton
class HybridRouter @Inject constructor() {
    fun decide(input: RoutingInput): RoutingDecision {
        if (input.forceCloud) {
            return RoutingDecision(Route.CLOUD_ONLY, listOf("@cloud 强制覆盖所有本地策略"))
        }

        if (input.localOnly) {
            return RoutingDecision(
                Route.LOCAL,
                listOf("用户明确选择仅本地生成，不允许云端 fallback")
            )
        }

        if (input.localAdmission == ModelAdmission.BLOCKED) {
            return RoutingDecision(
                Route.CLOUD_ONLY,
                buildList {
                    add("本地模型准入状态为 BLOCKED")
                    if (input.privacyLevel == PrivacyLevel.PRIVATE ||
                        input.privacyLevel == PrivacyLevel.SENSITIVE
                    ) {
                        add("本地模型不可用，无法满足端侧隐私策略")
                    }
                }
            )
        }

        val thermalHigh = input.thermalStatus in setOf(
            ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN
        )
        val batteryLow = input.batteryPercent in 0..15
        val recentFailure = input.recentLocalFailureRate >= RECENT_FAILURE_RISK_THRESHOLD

        if (input.privacyLevel == PrivacyLevel.SENSITIVE ||
            input.privacyLevel == PrivacyLevel.PRIVATE
        ) {
            return RoutingDecision(
                Route.LOCAL,
                listOf("内容隐私级别为 ${input.privacyLevel}，默认留在端侧")
            )
        }

        if (input.networkState == NetworkState.OFFLINE) {
            return RoutingDecision(
                Route.LOCAL,
                listOf("当前网络离线，使用本地模型")
            )
        }

        if (input.localAdmission == ModelAdmission.RISKY &&
            (batteryLow || thermalHigh || input.powerSaveMode)
        ) {
            return RoutingDecision(
                Route.CLOUD_ONLY,
                buildList {
                    add("本地模型准入状态为 RISKY")
                    if (batteryLow) add("当前电量偏低")
                    if (thermalHigh) add("设备热状态偏高")
                    if (input.powerSaveMode) add("设备处于省电模式")
                    add("避免在当前设备状态加载本地模型")
                }
            )
        }

        if (input.networkState == NetworkState.METERED) {
            return RoutingDecision(
                Route.LOCAL,
                listOf("当前网络按流量计费，避免自动使用云端或云端 fallback")
            )
        }

        if (recentFailure) {
            return RoutingDecision(
                Route.CLOUD_ONLY,
                listOf(
                    "近期本地失败率较高（${input.recentLocalFailureRate.formatPercent()}）",
                    "本次默认使用云端"
                )
            )
        }

        if (input.taskComplexity == TaskComplexity.COMPLEX) {
            return RoutingDecision(
                Route.CLOUD,
                listOf("任务复杂度为 COMPLEX，优先使用云端能力")
            )
        }

        if (input.latencyBudgetMs != null && input.latencyBudgetMs <= LOW_LATENCY_BUDGET_MS) {
            return RoutingDecision(
                Route.LOCAL_THEN_CLOUD,
                listOf("延迟预算较低，优先尝试本地模型")
            )
        }

        return RoutingDecision(
            Route.LOCAL_THEN_CLOUD,
            listOf("常规任务，本地优先并允许云端兜底")
        )
    }

    private fun Float.formatPercent(): String {
        return "%.0f%%".format(this * 100f)
    }

    private companion object {
        const val LOW_LATENCY_BUDGET_MS = 1_500L
        const val RECENT_FAILURE_RISK_THRESHOLD = 0.34f
    }
}
