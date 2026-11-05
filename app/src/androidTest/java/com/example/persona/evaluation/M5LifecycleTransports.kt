package com.example.persona.evaluation

import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.StopReason
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.dto.ChatRequest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

internal data class M5Dispatch(
    val backend: Backend,
    val userBody: String,
    val messages: List<ChatMessage>,
    val sessionId: String? = null
)

// Synthetic transport boundaries only; outputs and metrics are not LLM quality evidence.
internal class M5ControlledLocalEngine(
    private val onDispatch: (M5Dispatch) -> Unit
) : LocalAiEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    val stoppedSessions = CopyOnWriteArrayList<GenerationSession>()

    @Volatile
    var failNext = false

    // Deliberately fake: never initialize JNI or open the dummy model with MNN.
    override suspend fun initialize(model: InstalledModel): Boolean {
        state.value = EngineState.Ready
        return true
    }

    override suspend fun smokeTest(model: InstalledModel): Boolean = true

    override fun streamResponse(
        session: GenerationSession,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): Flow<GenerationEvent> = flow {
        onDispatch(M5Dispatch(Backend.MNN, prompt, history.toList(), session.id))
        val fail = failNext
        failNext = false
        session.stopReason?.let {
            emit(GenerationEvent.Stopped(it, ""))
            return@flow
        }
        val text = if (fail) "SYNTHETIC_LOCAL_PARTIAL" else "Synthetic executor response"
        emit(GenerationEvent.Delta(text))
        session.stopReason?.let {
            emit(GenerationEvent.Stopped(it, text))
            return@flow
        }
        if (fail) {
            emit(GenerationEvent.Failed(
                backend = Backend.MNN,
                error = GenerationError(GenerationErrorType.MODEL_RUNTIME, "Synthetic local runtime failure"),
                partialText = text
            ))
        } else {
            emit(GenerationEvent.Completed(GenerationMetrics(
                firstTokenLatencyMs = 1L,
                totalLatencyMs = 1L,
                outputTokens = 3,
                tokensPerSecond = 3000.0
            )))
        }
    }

    override fun stopGeneration(session: GenerationSession) {
        stoppedSessions += session
        session.requestStop(StopReason.USER_REQUESTED)
    }

    override fun release() {
        state.value = EngineState.Idle
    }
}

internal class M5ControlledCloudApi(
    private val onDispatch: (M5Dispatch) -> Unit
) : CloudChatApi {
    override fun streamChat(request: ChatRequest, authorization: String?): Call<ResponseBody> {
        val messages = request.messages.map { ChatMessage(it.role, it.content) }
        onDispatch(M5Dispatch(
            backend = Backend.CLOUD,
            userBody = messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
            messages = messages
        ))
        return SyntheticSseCall()
    }

    // No client, socket, credentials, or request payload is needed for this in-memory call.
    private class SyntheticSseCall : Call<ResponseBody> {
        private val executed = AtomicBoolean(false)
        private val canceled = AtomicBoolean(false)
        private val callTimeout = Timeout()

        override fun execute(): Response<ResponseBody> {
            check(executed.compareAndSet(false, true)) { "Already executed" }
            if (canceled.get()) throw IOException("Canceled")
            val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Synthetic executor response\"}}]}\n\n" +
                "data: [DONE]\n\n"
            return Response.success(sse.toResponseBody("text/event-stream; charset=utf-8".toMediaType()))
        }

        override fun enqueue(callback: Callback<ResponseBody>) {
            val response = try {
                execute()
            } catch (error: IOException) {
                callback.onFailure(this, error)
                return
            }
            callback.onResponse(this, response)
        }

        override fun cancel() { canceled.set(true) }
        override fun isCanceled(): Boolean = canceled.get()
        override fun isExecuted(): Boolean = executed.get()
        override fun clone(): Call<ResponseBody> = SyntheticSseCall()
        override fun request(): Request = Request.Builder()
            .url("https://m5-lifecycle.invalid/api/chat/completions")
            .build()
        override fun timeout(): Timeout = callTimeout
    }
}
