package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationSessionTest {
    @Test
    fun `a new request is not stopped`() {
        assertNull(GenerationSession().stopReason)
    }

    @Test
    fun `a later release preserves the original stop reason`() {
        val session = GenerationSession()
        session.requestStop(StopReason.USER_REQUESTED)
        session.requestStop(StopReason.ENGINE_RELEASED)

        assertEquals(StopReason.USER_REQUESTED, session.stopReason)
    }

    @Test(expected = IllegalStateException::class)
    fun `a request cannot replace its original route authorization`() {
        val session = GenerationSession()
        session.bindRoutingDecision(RoutingDecision(Route.LOCAL, emptyList()))
        session.bindRoutingDecision(RoutingDecision(Route.CLOUD_ONLY, emptyList()))
    }
}
