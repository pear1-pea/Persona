package com.example.persona.core.ai

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class GenerationContext(
    val systemPrompt: String,
    val history: List<ChatMessage>,
    val userMessage: String? = null
)

data class GenerationSession(
    val id: String = UUID.randomUUID().toString()
) {
    private val requestedStop = AtomicReference<StopReason?>(null)
    private val routeAuthorization = AtomicReference<RoutingDecision?>(null)
    private val contextProvider = AtomicReference<(suspend (Backend) -> GenerationContext)?>(null)

    fun bindContextProvider(provider: suspend (Backend) -> GenerationContext) {
        check(contextProvider.compareAndSet(null, provider)) { "Request context is already bound" }
    }

    suspend fun prepareContext(backend: Backend, systemPrompt: String, history: List<ChatMessage>): GenerationContext =
        contextProvider.get()?.invoke(backend) ?: GenerationContext(systemPrompt, history)

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
