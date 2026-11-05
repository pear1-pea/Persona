package com.example.persona.evaluation

import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

internal class M5LifecycleGate(private val actual: ChatRepository) : ChatRepository by actual {
    class Wait {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val requestJob = CompletableDeferred<Job>()
    }

    private val next = AtomicReference<Wait?>(null)

    fun arm(): Wait = Wait().also { check(next.compareAndSet(null, it)) }

    override suspend fun saveMessage(message: Message, persona: Persona) {
        actual.saveMessage(message, persona)
        if (!message.isFromUser) return
        val wait = next.getAndSet(null) ?: return
        wait.requestJob.complete(currentCoroutineContext().job)
        wait.entered.complete(Unit)
        try {
            wait.release.await()
        } finally {
            wait.finished.complete(Unit)
        }
    }
}
