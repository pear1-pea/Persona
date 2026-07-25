package com.example.persona.core.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class EdgeAiEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    @Volatile
    private var llmInference: LlmInference? = null
    private val modelName = "gemma-2b-it-cpu-int4.bin"
    private val initMutex = Mutex()
    private val generateMutex = Mutex()
    private val channelRef = AtomicReference<SendChannel<String>?>(null)

    suspend fun initModel() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (llmInference != null) return@withLock

            val modelFile = File(context.filesDir, modelName)
            if (!modelFile.exists()) {
                Log.e("EdgeAiEngine", "❌ 文件不存在")
                return@withLock
            }

            try {
                Log.d("EdgeAiEngine", "🚀 开始加载 MediaPipe...")
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .setTemperature(0.7f)
                    .setRandomSeed(1234)
                    .setResultListener { partialResult, done ->
                        if (partialResult != null) {
                            channelRef.get()?.trySend(partialResult)
                        }
                        if (done) {
                            channelRef.get()?.close()
                        }
                    }
                    .setErrorListener { e ->
                        Log.e("EdgeAiEngine", "❌ 内部错误: ${e.message}")
                        channelRef.get()?.close(RuntimeException(e.message))
                    }
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                Log.d("EdgeAiEngine", "✅ MediaPipe 加载成功!")
            } catch (e: Exception) {
                Log.e("EdgeAiEngine", "❌ 加载崩溃", e)
            }
        }
    }

    suspend fun routeComplexity(userText: String): Float = withContext(Dispatchers.IO) {
        if (llmInference == null) return@withContext 0.0f

        val prompt = "Classify: $userText. A=Simple, B=Complex. Output A or B."

        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                val result = llmInference?.generateResponse(prompt) ?: "A"
                if (result.contains("B", ignoreCase = true)) continuation.resume(1.0f)
                else continuation.resume(0.0f)
            } catch (e: Exception) {
                Log.e("EdgeAiEngine", "Router Error", e)
                continuation.resume(0.0f)
            }
        }
    }

    fun generateResponse(systemPrompt: String, userText: String): Flow<String> = callbackFlow {
        generateMutex.withLock {
            if (llmInference == null) {
                trySend("[Error] 模型未加载")
                close()
                return@callbackFlow
            }

            channelRef.set(channel)

            val fullPrompt = "$systemPrompt\n\nUser: $userText\nModel:"

            try {
                Log.d("EdgeAiEngine", "Generating response...")
                llmInference?.generateResponseAsync(fullPrompt)
            } catch (e: Exception) {
                Log.e("EdgeAiEngine", "Generation Error", e)
                channelRef.set(null)
                close(e)
                return@callbackFlow
            }

            awaitClose {
                channelRef.set(null)
            }
        }
    }
}