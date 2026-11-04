package com.example.persona.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationSessionTest {
    @Test
    fun `context is prepared again for each execution backend`() = kotlinx.coroutines.runBlocking {
        val session = GenerationSession()
        var revision = 1
        session.bindContextProvider { backend -> GenerationContext("$backend/$revision", emptyList()) }
        assertEquals("MNN/1", session.prepareContext(Backend.MNN, "base", emptyList()).systemPrompt)
        revision = 2
        assertEquals("CLOUD/2", session.prepareContext(Backend.CLOUD, "base", emptyList()).systemPrompt)
    }

    @Test(expected = IllegalStateException::class)
    fun `a request cannot replace its context owner`() {
        val session = GenerationSession()
        session.bindContextProvider { GenerationContext("one", emptyList()) }
        session.bindContextProvider { GenerationContext("two", emptyList()) }
    }

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
