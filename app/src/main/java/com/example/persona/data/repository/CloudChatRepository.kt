package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.prompt.CloudContextPlanner
import com.example.persona.core.ai.prompt.ContextBudgetExceeded
import com.example.persona.core.ai.prompt.ConservativeTokenizer
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.core.auth.AuthIdentity
import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.CloudGenerationException
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.SseDataReader
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.remote.dto.ChatResponse
import com.example.persona.data.remote.dto.MessageDto
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import javax.inject.Inject
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.net.SocketTimeoutException

class CloudChatRepository internal constructor(
    private val api: CloudChatApi,
    private val config: BackendConfig,
    private val authTokenProvider: AuthTokenProvider,
    private val progressTimeoutMs: Long
) {
    @Inject constructor(api: CloudChatApi, config: BackendConfig, authTokenProvider: AuthTokenProvider) :
        this(api, config, authTokenProvider, 90_000L)

    fun streamResponse(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList()
    ): Flow<GenerationEvent> = streamResponseWithParams(systemPrompt, userMessage, history, GenerationParams())

    fun streamResponseWithParams(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): Flow<GenerationEvent> {
        val requestedToken = authTokenProvider.currentToken()
        val manager = authTokenProvider as? AuthManager
        val requestedIdentity = manager?.identity?.value
        return callbackFlow {
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val partialText = StringBuffer()
        val terminal = AtomicBoolean()
        fun finish(event: GenerationEvent) {
            if (terminal.compareAndSet(false, true)) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    send(event)
                    close()
                }
            }
        }
        val contextIdentity = currentCoroutineContext()[AuthIdentity]
        val cloudToken = if (contextIdentity != null) contextIdentity.token else requestedToken
        if (!config.isConfigured || cloudToken.isNullOrBlank()) {
            send(
                GenerationEvent.Failed(
                    backend = Backend.CLOUD,
                    error = GenerationError(
                        type = GenerationErrorType.NOT_CONFIGURED,
                        message = if (!config.isConfigured) "请配置 Backend 地址" else "请重新登录后使用云端"
                    ),
                    partialText = ""
                )
            )
            close()
            return@callbackFlow
        }

        currentCoroutineContext().ensureActive()
        if (authTokenProvider.currentToken() != cloudToken) {
            close(CancellationException("Account or session changed"))
            return@callbackFlow
        }
        val messages = try {
            CloudContextPlanner.plan(systemPrompt, userMessage, history).map { MessageDto(it.role, it.content) }
        } catch (error: ContextBudgetExceeded) {
            send(GenerationEvent.Failed(Backend.CLOUD,
                GenerationError(GenerationErrorType.INPUT_BUDGET_EXCEEDED, error.message.orEmpty()), ""))
            close()
            return@callbackFlow
        }
        val identityObserver = if (manager != null) launch {
            val expected = contextIdentity ?: requestedIdentity
            manager.identity.first { it !== expected }
            close(CancellationException("Account or session changed"))
        } else null

        val request = ChatRequest(
            model = config.modelId.ifBlank { DEFAULT_DEEPSEEK_MODEL },
            messages = messages,
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens
        )

        val call = api.streamChat(request, "Bearer $cloudToken")
        val source = AtomicReference<BufferedReader?>()
        val readerJob = AtomicReference<kotlinx.coroutines.Job?>()
        val lastProgress = AtomicLong(System.nanoTime())
        val progressGuard = launch {
            while (isActive && !terminal.get()) {
                delay(minOf(1_000L, progressTimeoutMs.coerceAtLeast(1L)))
                if ((System.nanoTime() - lastProgress.get()) / 1_000_000L >= progressTimeoutMs) {
                    finish(GenerationEvent.Failed(Backend.CLOUD,
                        GenerationError(GenerationErrorType.NETWORK, "云端长时间没有有效输出",
                            SocketTimeoutException("No generation progress")), partialText.toString()))
                    call.cancel()
                    break
                }
            }
        }

        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.code() == 401) authTokenProvider.invalidateSession(cloudToken)
                if (!isActive || authTokenProvider.currentToken() != cloudToken) {
                    response.body()?.close()
                    response.errorBody()?.close()
                    close(CancellationException("Account or session changed"))
                    return
                }
                if (!response.isSuccessful) {
                    response.errorBody()?.close()
                    Log.e(TAG, "Backend error ${response.code()}")
                    finish(
                        GenerationEvent.Failed(
                            backend = Backend.CLOUD,
                            error = GenerationError(
                                type = GenerationErrorType.HTTP,
                                message = "云端请求失败: HTTP ${response.code()}",
                                cause = CloudGenerationException.HttpError(response.code())
                            ),
                            partialText = partialText.toString()
                        )
                    )
                    return
                }

                val body = response.body()
                if (body == null) {
                    finish(
                        GenerationEvent.Failed(
                            backend = Backend.CLOUD,
                            error = GenerationError(
                                type = GenerationErrorType.NETWORK,
                                message = "云端返回为空"
                            ),
                            partialText = partialText.toString()
                        )
                    )
                    return
                }

                if (!isActive) {
                    body.close()
                    return
                }

                val bodyReader = body.byteStream().bufferedReader()
                source.set(bodyReader)
                if (!isActive) {
                    bodyReader.close()
                    return
                }
                readerJob.set(launch(Dispatchers.IO) {
                    val gson = Gson()
                    var receivedDone = false
                    try {
                        val frames = SseDataReader(bodyReader)
                        var line: String? = frames.readData()
                        while (line != null) {
                            currentCoroutineContext().ensureActive()
                            if (line.isNotBlank()) {
                                val jsonStr = line.trim()

                                if (jsonStr == "[DONE]") {
                                    receivedDone = true
                                    break
                                }

                                val content = try {
                                    gson.fromJson(jsonStr, ChatResponse::class.java)
                                        ?.choices?.firstOrNull()?.delta?.content
                                } catch (error: Exception) {
                                    throw java.io.IOException("Invalid SSE payload", error)
                                }
                                content?.takeIf { it.isNotEmpty() }?.let { content ->
                                    lastProgress.set(System.nanoTime())
                                    if (firstTokenAt == null) {
                                        firstTokenAt = System.nanoTime()
                                    }
                                    partialText.append(content)
                                    send(GenerationEvent.Delta(content))
                                }
                            }
                            line = frames.readData()
                        }
                        val totalLatencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                        val outputTokenCount = ConservativeTokenizer.countTokens(partialText.toString())
                        if (receivedDone) {
                            finish(
                                GenerationEvent.Completed(
                                    GenerationMetrics(
                                        firstTokenLatencyMs = firstTokenAt?.let {
                                            (it - startedAt) / 1_000_000L
                                        },
                                        totalLatencyMs = totalLatencyMs,
                                        outputTokens = outputTokenCount,
                                        tokensPerSecond = outputTokenCount.toRate(totalLatencyMs)
                                    )
                                )
                            )
                        } else {
                            finish(
                                GenerationEvent.Failed(
                                    backend = Backend.CLOUD,
                                    error = GenerationError(
                                        type = GenerationErrorType.NETWORK,
                                        message = "云端流在完成前断开",
                                        cause = CloudGenerationException.Network(
                                            IllegalStateException("SSE stream ended before [DONE]")
                                        )
                                    ),
                                    partialText = partialText.toString()
                                )
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e(TAG, "Cloud stream read failed: ${e.javaClass.simpleName}")
                        finish(
                            GenerationEvent.Failed(
                                backend = Backend.CLOUD,
                                error = GenerationError(
                                    type = GenerationErrorType.NETWORK,
                                    message = "云端网络错误",
                                    cause = CloudGenerationException.Network(e)
                                ),
                                partialText = partialText.toString()
                            )
                        )
                    } finally {
                        bodyReader.close()
                    }
                })
            }

            override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                if (error is CancellationException) {
                    close(error)
                } else {
                    finish(
                        GenerationEvent.Failed(
                            backend = Backend.CLOUD,
                            error = GenerationError(
                                type = GenerationErrorType.NETWORK,
                                message = "云端网络错误",
                                cause = CloudGenerationException.Network(error)
                            ),
                            partialText = partialText.toString()
                        )
                    )
                }
            }
        })

        awaitClose {
            progressGuard.cancel()
            identityObserver?.cancel()
            call.cancel()
            readerJob.get()?.cancel()
            source.get()?.close()
        }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun generatePersonaProfile(keywords: String): String {
        val systemPrompt = """
            You are a creative character designer.
            Task: Create a unique persona based on user keywords.

            IMPORTANT: You must return ONLY a raw JSON object. Do not wrap it in markdown code blocks (like ```json).
            The JSON structure must be:
            {
              "name": "Character Name",
              "backstory": "A short, engaging biography (under 100 words).",
              "traits": ["Trait1", "Trait2", "Trait3"]
            }
        """.trimIndent()

        val userPrompt = if (keywords.isBlank()) "Theme: Sci-Fi, Mysterious" else "Keywords: $keywords"
        val fullResponseBuilder = StringBuilder()
        var failure: GenerationEvent.Failed? = null

        streamResponse(systemPrompt, userPrompt).collect { event ->
            when (event) {
                is GenerationEvent.Delta -> fullResponseBuilder.append(event.text)
                is GenerationEvent.Failed -> failure = event
                else -> Unit
            }
        }

        failure?.let { event ->
            throw event.error.cause ?: IllegalStateException(event.error.message)
        }

        return fullResponseBuilder.toString()
    }

    private companion object {
        const val TAG = "CloudChatRepository"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
    }
}

private fun Int.toRate(elapsedMs: Long): Double {
    return if (elapsedMs > 0L) this * 1000.0 / elapsedMs else 0.0
}
