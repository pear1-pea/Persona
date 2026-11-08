package com.example.persona.core.ai

import com.example.persona.core.ai.telemetry.ContractStatus
import com.example.persona.core.ai.telemetry.InferenceAttempt
import com.example.persona.core.ai.telemetry.InferenceTelemetry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BufferedInferenceTelemetry(
    private val delegate: InferenceTelemetry,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    capacity: Int = 64
) : InferenceTelemetry {
    private sealed interface Command {
        data class Record(val value: InferenceAttempt) : Command
        data class Contract(val requestHash: String, val attempt: Int, val status: ContractStatus) : Command
    }
    private val queue = Channel<Command>(capacity.also { require(it > 0) })
    private val dropped = AtomicLong()
    private val failed = AtomicLong()
    val droppedCommands: Long get() = dropped.get()
    val failedCommands: Long get() = failed.get()

    init {
        scope.launch {
            for (command in queue) {
                try {
                    when (command) {
                        is Command.Record -> delegate.record(command.value)
                        is Command.Contract -> delegate.recordContract(command.requestHash, command.attempt, command.status)
                    }
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    failed.incrementAndGet()
                } catch (_: Exception) {
                    failed.incrementAndGet()
                }
            }
        }
    }

    override suspend fun record(attempt: InferenceAttempt) { enqueue(Command.Record(attempt)) }

    override suspend fun recordContract(requestHash: String, attempt: Int, status: ContractStatus) {
        enqueue(Command.Contract(requestHash, attempt, status))
    }

    private fun enqueue(command: Command) {
        // Runtime completion and cancellation never wait for journal IO or queue capacity.
        if (queue.trySend(command).isFailure) dropped.incrementAndGet()
    }

    fun close() { queue.close() }
}
