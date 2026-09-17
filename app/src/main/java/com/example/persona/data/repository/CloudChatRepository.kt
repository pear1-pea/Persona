package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.prompt.ConservativeTokenizer
import com.example.persona.data.remote.CloudGenerationException
import com.example.persona.data.remote.DeepSeekApi
import com.example.persona.data.remote.DeepSeekConfig
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.remote.dto.ChatResponse
import com.example.persona.data.remote.dto.MessageDto
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import javax.inject.Inject
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicReference

class CloudChatRepository @Inject constructor(
    private val api: DeepSeekApi,
    private val config: DeepSeekConfig
) {
    fun streamResponse(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList()
    ): Flow<GenerationEvent> = callbackFlow {
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val partialText = StringBuilder()
        if (config.apiKey.isBlank()) {
            trySend(
                GenerationEvent.Failed(
                    backend = Backend.CLOUD,
                    error = GenerationError(
                        type = GenerationErrorType.NOT_CONFIGURED,
                        message = "云端 API 未配置"
                    ),
                    partialText = ""
                )
            )
            close()
            return@callbackFlow
        }

        val messages = buildMessages(systemPrompt, userMessage, history)
        val request = ChatRequest(
            model = config.modelId.ifBlank { DEFAULT_DEEPSEEK_MODEL },
            messages = messages
        )

        val call = api.streamChat(request)
        val source = AtomicReference<BufferedReader?>()
        val readerJob = AtomicReference<kotlinx.coroutines.Job?>()

        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string().orEmpty()
                    Log.e(TAG, "DeepSeek error ${response.code()}: $errorBody")
                    trySend(
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
                    close()
                    return
                }

                val body = response.body()
                if (body == null) {
                    trySend(
                        GenerationEvent.Failed(
                            backend = Backend.CLOUD,
                            error = GenerationError(
                                type = GenerationErrorType.NETWORK,
                                message = "云端返回为空"
                            ),
                            partialText = partialText.toString()
                        )
                    )
                    close()
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
                        var line: String? = bodyReader.readLine()
                        while (line != null) {
                            currentCoroutineContext().ensureActive()
                            if (line.startsWith("data:")) {
                                val jsonStr = line.substring(5).trim()

                                if (jsonStr == "[DONE]") {
                                    receivedDone = true
                                    break
                                }

                                runCatching {
                                    val chatResponse = gson.fromJson(jsonStr, ChatResponse::class.java)
                                    chatResponse.choices.firstOrNull()?.delta?.content
                                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { content ->
                                    if (firstTokenAt == null) {
                                        firstTokenAt = System.nanoTime()
                                    }
                                    partialText.append(content)
                                    send(GenerationEvent.Delta(content))
                                }
                            }
                            line = bodyReader.readLine()
                        }
                        val totalLatencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                        val outputTokenCount = ConservativeTokenizer.countTokens(partialText.toString())
                        if (receivedDone) {
                            trySend(
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
                            trySend(
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
                        close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e(TAG, "DeepSeek stream read error", e)
                        trySend(
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
                        close()
                    } finally {
                        bodyReader.close()
                    }
                })
            }

            override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                if (error is CancellationException) {
                    close(error)
                } else {
                    trySend(
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
                    close()
                }
            }
        })

        awaitClose {
            call.cancel()
            readerJob.get()?.cancel()
            source.get()?.close()
        }
    }.flowOn(Dispatchers.IO)

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

    private fun buildMessages(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage>
    ): List<MessageDto> {
        return buildList {
            add(MessageDto("system", systemPrompt))
            history
                .filter { it.content.isNotBlank() && it.role != "system" }
                .takeLast(CLOUD_HISTORY_LIMIT)
                .forEach { message ->
                    add(
                        MessageDto(
                            role = when (message.role) {
                                "assistant" -> "assistant"
                                else -> "user"
                            },
                            content = message.content
                        )
                    )
                }
            add(MessageDto("user", userMessage))
        }
    }

    private companion object {
        const val TAG = "CloudChatRepository"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val CLOUD_HISTORY_LIMIT = 12
    }
}

private fun Int.toRate(elapsedMs: Long): Double {
    return if (elapsedMs > 0L) this * 1000.0 / elapsedMs else 0.0
}
