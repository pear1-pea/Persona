package com.example.persona.core.ai

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class GenerationSession(
    val id: String = UUID.randomUUID().toString()
) {
    private val requestedStop = AtomicReference<StopReason?>(null)
    private val routeAuthorization = AtomicReference<RoutingDecision?>(null)

    val routingDecision: RoutingDecision?
        get() = routeAuthorization.get()

    fun bindRoutingDecision(decision: RoutingDecision) {
        check(routeAuthorization.compareAndSet(null, decision)) { "Request route is already bound" }
    }

    val stopReason: StopReason?
        get() = requestedStop.get()

    fun requestStop(reason: StopReason) {
        requestedStop.compareAndSet(null, reason)
    }
}
