package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRouterTest {

    private val router = HybridRouter()

    @Test
    fun `force cloud cannot override local only or privacy`() {
        val decision = router.decide(
            input(forceCloud = true, localOnly = true, privacyLevel = PrivacyLevel.SENSITIVE)
        )

        assertEquals(Route.LOCAL, decision.route)
        assertTrue(decision.isLocalPreferred)
        assertFalse(decision.cloudAllowed)
    }

    @Test
    fun `local only is returned when it is the explicit user choice`() {
        val decision = router.decide(input(localOnly = true))

        assertEquals(Route.LOCAL, decision.route)
        assertTrue(decision.isLocalPreferred)
    }

    @Test
    fun `blocked local model routes to cloud`() {
        val decision = router.decide(input(localAdmission = ModelAdmission.BLOCKED))

        assertEquals(Route.CLOUD_ONLY, decision.route)
        assertTrue(decision.reasons.any { it.contains("BLOCKED") })
    }

    @Test
    fun `blocked local model explains that a private request cannot be served`() {
        val decision = router.decide(
            input(
                localAdmission = ModelAdmission.BLOCKED,
                privacyLevel = PrivacyLevel.PRIVATE
            )
        )

        assertEquals(Route.LOCAL, decision.route)
        assertFalse(decision.isExecutable)
        assertFalse(decision.cloudAllowed)
        assertTrue(decision.reasons.any { it.contains("隐私") })
    }

    @Test
    fun `private content stays local`() {
        val decision = router.decide(input(privacyLevel = PrivacyLevel.PRIVATE))

        assertEquals(Route.LOCAL, decision.route)
        assertTrue(decision.reasons.any { it.contains("PRIVATE") })
    }

    @Test
    fun `sensitive content stays local`() {
        val decision = router.decide(input(privacyLevel = PrivacyLevel.SENSITIVE))

        assertEquals(Route.LOCAL, decision.route)
    }

    @Test
    fun `offline generation uses local model`() {
        val decision = router.decide(input(networkState = NetworkState.OFFLINE))

        assertEquals(Route.LOCAL, decision.route)
        assertTrue(decision.reasons.any { it.contains("离线") })
    }

    @Test
    fun `risky model with low battery routes to cloud`() {
        val decision = router.decide(
            input(localAdmission = ModelAdmission.RISKY, batteryPercent = 10)
        )

        assertEquals(Route.CLOUD_ONLY, decision.route)
        assertTrue(decision.reasons.any { it.contains("电量") })
    }

    @Test
    fun `risky model with severe thermal state routes to cloud`() {
        val decision = router.decide(
            input(localAdmission = ModelAdmission.RISKY, thermalStatus = ThermalStatus.SEVERE)
        )

        assertEquals(Route.CLOUD_ONLY, decision.route)
        assertTrue(decision.reasons.any { it.contains("热") })
    }

    @Test
    fun `risky model in power save mode routes to cloud`() {
        val decision = router.decide(
            input(localAdmission = ModelAdmission.RISKY, powerSaveMode = true)
        )

        assertEquals(Route.CLOUD_ONLY, decision.route)
        assertTrue(decision.reasons.any { it.contains("省电") })
    }

    @Test
    fun `risky model without device stress still tries local first`() {
        val decision = router.decide(input(localAdmission = ModelAdmission.RISKY))

        assertEquals(Route.LOCAL_THEN_CLOUD, decision.route)
    }

    @Test
    fun `metered network prefers local to avoid data charges`() {
        val decision = router.decide(input(networkState = NetworkState.METERED))

        assertEquals(Route.LOCAL, decision.route)
        assertTrue(decision.reasons.any { it.contains("流量") })
    }

    @Test
    fun `blocked local retry routes to cloud`() {
        val decision = router.decide(input(localRetryBlocked = true))

        assertEquals(Route.CLOUD_ONLY, decision.route)
        assertTrue(decision.reasons.any { it.contains("退避") })
        assertTrue(decision.reasons.none { it.contains("%") })
    }

    @Test
    fun `complex tasks prefer cloud`() {
        val decision = router.decide(input(taskComplexity = TaskComplexity.COMPLEX))

        assertEquals(Route.CLOUD, decision.route)
    }

    @Test
    fun `normal task defaults to local then cloud`() {
        val decision = router.decide(input())

        assertEquals(Route.LOCAL_THEN_CLOUD, decision.route)
        assertTrue(decision.isLocalPreferred)
    }

    @Test
    fun `unknown network falls through to normal policy`() {
        val decision = router.decide(input(networkState = NetworkState.UNKNOWN))

        assertEquals(Route.LOCAL_THEN_CLOUD, decision.route)
    }

    @Test
    fun `unknown battery value does not look like a low battery`() {
        val decision = router.decide(
            input(localAdmission = ModelAdmission.RISKY, batteryPercent = -1)
        )

        assertEquals(Route.LOCAL_THEN_CLOUD, decision.route)
    }

    @Test
    fun `light thermal state does not force cloud by itself`() {
        val decision = router.decide(
            input(localAdmission = ModelAdmission.RISKY, thermalStatus = ThermalStatus.LIGHT)
        )

        assertEquals(Route.LOCAL_THEN_CLOUD, decision.route)
    }

    @Test
    fun `metered network without a usable local model fails without cloud`() {
        val decision = router.decide(input(networkState = NetworkState.METERED, localAdmission = ModelAdmission.BLOCKED))
        assertFalse(decision.isExecutable)
        assertFalse(decision.cloudAllowed)
    }

    @Test
    fun `offline force cloud cannot create a cloud request`() {
        val decision = router.decide(input(forceCloud = true, networkState = NetworkState.OFFLINE,
            localAdmission = ModelAdmission.BLOCKED))
        assertFalse(decision.isExecutable)
        assertFalse(decision.cloudAllowed)
    }

    @Test
    fun `private request cannot bypass thermal admission`() {
        val decision = router.decide(input(privacyLevel = PrivacyLevel.PRIVATE, thermalStatus = ThermalStatus.CRITICAL))
        assertFalse(decision.isExecutable)
        assertFalse(decision.cloudAllowed)
    }

    private fun input(
        forceCloud: Boolean = false,
        localOnly: Boolean = false,
        localAdmission: ModelAdmission = ModelAdmission.SUPPORTED,
        privacyLevel: PrivacyLevel = PrivacyLevel.NORMAL,
        taskComplexity: TaskComplexity = TaskComplexity.NORMAL,
        networkState: NetworkState = NetworkState.UNMETERED,
        batteryPercent: Int = 80,
        thermalStatus: ThermalStatus = ThermalStatus.NOMINAL,
        powerSaveMode: Boolean = false,
        localRetryBlocked: Boolean = false
    ): RoutingInput = RoutingInput(
        forceCloud = forceCloud,
        localOnly = localOnly,
        localAdmission = localAdmission,
        privacyLevel = privacyLevel,
        taskComplexity = taskComplexity,
        networkState = networkState,
        batteryPercent = batteryPercent,
        thermalStatus = thermalStatus,
        powerSaveMode = powerSaveMode,
        localRetryBlocked = localRetryBlocked
    )
}
