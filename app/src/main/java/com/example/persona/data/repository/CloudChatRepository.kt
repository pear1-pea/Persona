package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
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
    ): Flow<String> = callbackFlow {
        if (config.apiKey.isBlank()) {
            throw CloudGenerationException.NotConfigured()
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
                    close(CloudGenerationException.HttpError(response.code()))
                    return
                }

                val body = response.body()
                if (body == null) {
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
                    try {
                        var line: String? = bodyReader.readLine()
                        while (line != null) {
                            currentCoroutineContext().ensureActive()
                            if (line.startsWith("data:")) {
                                val jsonStr = line.substring(5).trim()

                                if (jsonStr == "[DONE]") break

                                runCatching {
                                    val chatResponse = gson.fromJson(jsonStr, ChatResponse::class.java)
                                    chatResponse.choices.firstOrNull()?.delta?.content
                                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { content ->
                                    send(content)
                                }
                            }
                            line = bodyReader.readLine()
                        }
                        close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e(TAG, "DeepSeek stream read error", e)
                        close(CloudGenerationException.Network(e))
                    } finally {
                        bodyReader.close()
                    }
                })
            }

            override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                if (error is CancellationException) {
                    close(error)
                } else {
                    close(CloudGenerationException.Network(error))
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

        streamResponse(systemPrompt, userPrompt).collect { token ->
            fullResponseBuilder.append(token)
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
