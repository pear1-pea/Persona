package com.example.persona.core.memory

import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

internal class InferencePriorityGate(private val stop: (GenerationSession) -> Unit) {
    private data class Request(val session: GenerationSession, val background: Boolean, val job: Job?)
    private val requests = mutableMapOf<String, Request>()

    fun begin(session: GenerationSession, background: Boolean, job: Job? = null): Boolean {
        val displaced: List<Request>
        synchronized(requests) {
            if (background && requests.isNotEmpty()) return false
            if (session.id in requests) return false
            displaced = if (background) emptyList() else requests.values.filter { it.background }
            displaced.forEach { it.session.requestStop(StopReason.SESSION_REPLACED) }
            requests[session.id] = Request(session, background, job)
        }
        displaced.forEach {
            stop(it.session)
            it.job?.cancel(CancellationException("Chat took priority over memory extraction"))
        }
        return true
    }

    suspend fun awaitBackgroundCleanup() {
        val jobs = synchronized(requests) { requests.values.filter { it.background }.mapNotNull { it.job } }
        jobs.joinAll()
    }

    fun end(session: GenerationSession) {
        synchronized(requests) { requests.remove(session.id) }
    }
}
