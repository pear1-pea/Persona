package com.example.persona.core.ai.mnn

import android.os.SystemClock
import android.util.Log
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationMetrics
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.StopReason
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
import com.example.persona.core.ai.prompt.Tokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MnnLocalAiEngine @Inject constructor(
    private val promptAdapterRegistry: PromptAdapterRegistry
) : LocalAiEngine {
    private val initializationMutex = Mutex()
    private val generationMutex = Mutex()
    private val runtimeLock = Any()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state = _state.asStateFlow()

    private data class RuntimeSnapshot(
        val nativeSession: NativeMnnRuntimeSession? = null,
        val model: InstalledModel? = null,
        val activeGeneration: GenerationSession? = null,
        val lifecycleVersion: Long = 0L
    )

    private var runtime = RuntimeSnapshot()
    private val stopReasons = mutableMapOf<String, StopReason>()

    override suspend fun initialize(model: InstalledModel): Boolean = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            if (model.backend != Backend.MNN) {
                synchronized(runtimeLock) {
                    _state.value = EngineState.Error("当前引擎不支持该模型后端")
                }
                return@withLock false
            }

            val configFile = File(model.modelDir, "config.json")
            if (!configFile.isFile) {
                synchronized(runtimeLock) {
                    _state.value = EngineState.Error("\u6a21\u578b\u914d\u7f6e\u6587\u4ef6\u4e0d\u5b58\u5728")
                }
                return@withLock false
            }

            if (synchronized(runtimeLock) {
                    runtime.nativeSession != null && runtime.model == model &&
                        _state.value == EngineState.Ready
                }) {
                return@withLock true
            }

            val startMs = SystemClock.elapsedRealtime()
            val (initializationVersion, nativeSessionToClose) = synchronized(runtimeLock) {
                val nextVersion = runtime.lifecycleVersion + 1
                _state.value = EngineState.Initializing
                val activeRuntime = runtime
                runtime = RuntimeSnapshot(lifecycleVersion = nextVersion)
                nextVersion to activeRuntime.nativeSession
            }
            nativeSessionToClose?.close()
            val nativeSession: NativeMnnRuntimeSession = NativeMnnSession()
            val loaded = try {
                val result = nativeSession.load(configFile.absolutePath)
                currentCoroutineContext().ensureActive()
                result
            } catch (error: CancellationException) {
                nativeSession.close()
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "MNN model load failed: ${model.id}", error)
                synchronized(runtimeLock) {
                    if (runtime.lifecycleVersion == initializationVersion) {
                        _state.value = EngineState.Error(error.message ?: "MNN \u6a21\u578b\u52a0\u8f7d\u5931\u8d25")
                    }
                }
                false
            }

            val committed = synchronized(runtimeLock) {
                if (runtime.lifecycleVersion != initializationVersion ||
                    _state.value != EngineState.Initializing
                ) {
                    false
                } else {
                    if (loaded) {
                        runtime = RuntimeSnapshot(
                            nativeSession = nativeSession,
                            model = model,
                            lifecycleVersion = initializationVersion
                        )
                        _state.value = EngineState.Ready
                        Log.i(TAG, "MNN model loaded: id=${model.id}, elapsedMs=${SystemClock.elapsedRealtime() - startMs}")
                    } else if (_state.value !is EngineState.Error) {
                        _state.value = EngineState.Error("MNN \u6a21\u578b\u52a0\u8f7d\u5931\u8d25")
                    }
                    true
                }
            }
            if (!committed) {
                nativeSession.close()
                return@withLock false
            }
            if (!loaded) {
                nativeSession.close()
            }
            loaded
        }
    }

    override suspend fun smokeTest(model: InstalledModel): Boolean = withContext(Dispatchers.IO) {
        val runtimeSnapshot = synchronized(runtimeLock) { runtime }
        val nativeSession = runtimeSnapshot.nativeSession
        if (runtimeSnapshot.model != model || nativeSession == null || _state.value != EngineState.Ready) {
            return@withContext false
        }

        val payload = try {
            promptAdapterRegistry.buildPayload(
                model = model,
                prompt = "ping",
                history = emptyList(),
                params = GenerationParams(
                    temperature = 0f,
                    topP = 1f,
                    maxTokens = 1
                ),
                tokenizer = object : Tokenizer {
                    override fun countTokens(text: String): Int = nativeSession.countTokens(text)
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "MNN smoke prompt build failed: model=${model.id}", error)
            return@withContext false
        }

        generationMutex.withLock {
            if (synchronized(runtimeLock) { runtime.activeGeneration != null }) {
                return@withLock false
            }
            val smokeSession = GenerationSession()
            synchronized(runtimeLock) {
                if (runtime.nativeSession !== nativeSession ||
                    runtime.model != model ||
                    _state.value != EngineState.Ready
                ) {
                    return@withLock false
                }
                runtime = runtime.copy(activeGeneration = smokeSession)
            }

            var completed = false
            var sawOutput = false
            var resetSucceeded = true
            try {
                nativeSession.generate(
                    payload = payload,
                    params = GenerationParams(
                        temperature = 0f,
                        topP = 1f,
                        maxTokens = 1
                    )
                ) { token ->
                    sawOutput = sawOutput || token.substringBefore(END_OF_PROMPT).isNotBlank()
                    true
                }
                completed = true
            } catch (error: CancellationException) {
                nativeSession.stop()
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "MNN smoke test failed: model=${model.id}", error)
            } finally {
                synchronized(runtimeLock) {
                    if (runtime.activeGeneration?.id == smokeSession.id &&
                        runtime.nativeSession === nativeSession
                    ) {
                        runtime = runtime.copy(activeGeneration = null)
                    }
                }
                resetSucceeded = try {
                    nativeSession.reset()
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "MNN smoke test reset failed: model=${model.id}", error)
                    false
                }
            }
            completed && sawOutput && resetSucceeded
        }
    }

    override fun streamResponse(
        session: GenerationSession,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): Flow<GenerationEvent> = callbackFlow {
        val runtimeSnapshot = synchronized(runtimeLock) { runtime }
        val nativeSession = runtimeSnapshot.nativeSession
        val model = runtimeSnapshot.model
        if (nativeSession == null || model == null || _state.value != EngineState.Ready) {
            trySend(
                GenerationEvent.Failed(
                    backend = Backend.MNN,
                    error = GenerationError(
                        type = GenerationErrorType.MODEL_NOT_READY,
                        message = "\u672c\u5730\u6a21\u578b\u5c1a\u672a\u5c31\u7eea"
                    ),
                    partialText = ""
                )
            )
            close()
            return@callbackFlow
        }
        val payload = try {
            promptAdapterRegistry.buildPayload(
                model = model,
                prompt = prompt,
                history = history,
                params = params,
                tokenizer = object : Tokenizer {
                    override fun countTokens(text: String): Int {
                        return nativeSession.countTokens(text)
                    }
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            trySend(
                GenerationEvent.Failed(
                    backend = Backend.MNN,
                    error = GenerationError(
                        type = GenerationErrorType.MODEL_RUNTIME,
                        message = error.message ?: "Prompt 构建失败",
                        cause = error
                    ),
                    partialText = ""
                )
            )
            close()
            return@callbackFlow
        }

        synchronized(runtimeLock) {
            if (runtime.nativeSession !== nativeSession ||
                _state.value != EngineState.Ready
            ) {
                trySend(
                    GenerationEvent.Stopped(
                        reason = StopReason.SESSION_REPLACED,
                        partialText = ""
                    )
                )
                close()
                return@callbackFlow
            }
            runtime = runtime.copy(activeGeneration = session)
        }
        val generationJob = launch(Dispatchers.IO) {
            generationMutex.withLock {
                var chunkCount = 0
                var outputCodePointCount = 0
                var firstChunkMs: Long? = null
                val generationStartMs = SystemClock.elapsedRealtime()
                var generationFailed = false
                val partialText = StringBuilder()
                try {
                    val shouldGenerate = synchronized(runtimeLock) {
                        runtime.activeGeneration?.id == session.id &&
                            runtime.nativeSession === nativeSession
                    }
                    if (shouldGenerate) {
                        nativeSession.generate(payload, params) { token ->
                            val ownsSession = synchronized(runtimeLock) {
                                runtime.activeGeneration?.id == session.id &&
                                    runtime.nativeSession === nativeSession
                            }
                            if (!ownsSession) {
                                false
                            } else {
                                val delta = sanitizeChunk(token)
                                if (delta.isNotEmpty()) {
                                    chunkCount += 1
                                    outputCodePointCount += delta.codePointCount(0, delta.length)
                                    partialText.append(delta)
                                    if (firstChunkMs == null) {
                                        firstChunkMs = SystemClock.elapsedRealtime() - generationStartMs
                                    }
                                }
                                delta.isEmpty() ||
                                    trySendBlocking(GenerationEvent.Delta(delta)).isSuccess
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    generationFailed = true
                    val ownsActiveSession = synchronized(runtimeLock) {
                        if (runtime.activeGeneration?.id != session.id) {
                            false
                        } else {
                            runtime = runtime.copy(activeGeneration = null)
                            _state.value = EngineState.Error(error.message ?: "MNN \u751f\u6210\u5931\u8d25")
                            true
                        }
                    }
                    if (ownsActiveSession) {
                        Log.e(TAG, "MNN generation failed: session=${session.id}", error)
                        trySend(
                            GenerationEvent.Failed(
                                backend = Backend.MNN,
                                error = GenerationError(
                                    type = GenerationErrorType.MODEL_RUNTIME,
                                    message = error.message ?: "MNN 生成失败",
                                    cause = error
                                ),
                                partialText = partialText.toString()
                            )
                        )
                        close()
                    } else {
                        trySend(
                            GenerationEvent.Stopped(
                                reason = synchronized(runtimeLock) {
                                    stopReasons.remove(session.id) ?: StopReason.SESSION_REPLACED
                                },
                                partialText = partialText.toString()
                            )
                        )
                        close()
                    }
                }
                if (!generationFailed && currentCoroutineContext().isActive) {
                    val completedWithOwnership = synchronized(runtimeLock) {
                        if (runtime.activeGeneration?.id == session.id &&
                            runtime.nativeSession === nativeSession
                        ) {
                            runtime = runtime.copy(activeGeneration = null)
                            true
                        } else {
                            false
                        }
                    }
                    if (!completedWithOwnership) {
                        trySend(
                            GenerationEvent.Stopped(
                                reason = synchronized(runtimeLock) {
                                    stopReasons.remove(session.id) ?: StopReason.SESSION_REPLACED
                                },
                                partialText = partialText.toString()
                            )
                        )
                        close()
                        return@withLock
                    }

                    val elapsedMs = SystemClock.elapsedRealtime() - generationStartMs
                    val outputTokenCount = try {
                        nativeSession.countTokens(partialText.toString())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(TAG, "MNN output token count unavailable", error)
                        outputCodePointCount
                    }
                    val outputTokensPerSecond = if (elapsedMs > 0) {
                        outputTokenCount * 1000.0 / elapsedMs
                    } else {
                        0.0
                    }
                    Log.i(
                        TAG,
                        "MNN generation finished: session=${session.id}, " +
                            "firstChunkMs=${firstChunkMs ?: -1}, chunks=$chunkCount, " +
                            "outputTokens=$outputTokenCount, " +
                            "outputTokensPerSecond=$outputTokensPerSecond, elapsedMs=$elapsedMs"
                    )
                    trySend(
                        GenerationEvent.Completed(
                            GenerationMetrics(
                                firstTokenLatencyMs = firstChunkMs,
                                totalLatencyMs = elapsedMs,
                                outputTokens = outputTokenCount,
                                tokensPerSecond = outputTokenCount.toRate(elapsedMs)
                            )
                        )
                    )
                    close()
                }
            }
        }

        awaitClose {
            val sessionToStop = synchronized(runtimeLock) {
                if (runtime.activeGeneration?.id != session.id) {
                    null
                } else {
                    runtime = runtime.copy(activeGeneration = null)
                    nativeSession
                }
            }
            sessionToStop?.stop()
            generationJob.cancel()
        }
    }

    override fun stopGeneration(session: GenerationSession) {
        val nativeSession = synchronized(runtimeLock) {
            if (runtime.activeGeneration?.id != session.id) {
                null
            } else {
                stopReasons[session.id] = StopReason.USER_REQUESTED
                runtime = runtime.copy(activeGeneration = null)
                runtime.nativeSession
            }
        }
        nativeSession?.stop()
    }

    override fun release() {
        val nativeSessionToClose = synchronized(runtimeLock) {
            val nextVersion = runtime.lifecycleVersion + 1
            _state.value = EngineState.Idle
            val activeRuntime = runtime
            activeRuntime.activeGeneration?.let { generation ->
                stopReasons[generation.id] = StopReason.ENGINE_RELEASED
            }
            runtime = RuntimeSnapshot(lifecycleVersion = nextVersion)
            activeRuntime.nativeSession
        }
        nativeSessionToClose?.close()
    }

    private fun sanitizeChunk(rawChunk: String): String {
        return rawChunk.substringBefore(END_OF_PROMPT)
    }

    private companion object {
        const val TAG = "MnnLocalAiEngine"
        const val END_OF_PROMPT = "<eop>"
    }
}

private fun Int.toRate(elapsedMs: Long): Double {
    return if (elapsedMs > 0L) this * 1000.0 / elapsedMs else 0.0
}
