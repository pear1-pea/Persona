package com.example.persona.core.ai.mnn

import android.os.SystemClock
import android.util.Log
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationParams
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.prompt.PromptAdapterRegistry
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
    // Protects active session references and model lifecycle versioning.
    // NativeMnnSession serializes generate/destroy while leaving stop non-blocking.
    private val nativeCallLock = Any()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state = _state.asStateFlow()

    @Volatile
    private var session: NativeMnnSession? = null

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var loadedModel: InstalledModel? = null

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var activeNativeSession: NativeMnnSession? = null

    @Volatile
    private var lifecycleVersion = 0L

    override suspend fun initialize(model: InstalledModel): Boolean = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            if (model.backend != Backend.MNN) {
                _state.value = EngineState.Error("当前引擎不支持该模型后端")
                return@withLock false
            }

            val configFile = File(model.modelDir, "config.json")
            if (!configFile.isFile) {
                _state.value = EngineState.Error("\u6a21\u578b\u914d\u7f6e\u6587\u4ef6\u4e0d\u5b58\u5728")
                return@withLock false
            }

            if (synchronized(nativeCallLock) {
                    session != null && loadedModel == model && _state.value == EngineState.Ready
                }
            ) {
                return@withLock true
            }

            val startMs = SystemClock.elapsedRealtime()
            val (initializationVersion, nativeSessionToStop) = synchronized(nativeCallLock) {
                lifecycleVersion += 1
                _state.value = EngineState.Initializing
                activeSessionId = null
                val activeSession = activeNativeSession
                activeNativeSession = null
                lifecycleVersion to activeSession
            }
            nativeSessionToStop?.stop()
            synchronized(nativeCallLock) {
                releaseInternal()
            }
            val nativeSession = NativeMnnSession()
            val loaded = try {
                val result = nativeSession.load(configFile.absolutePath)
                currentCoroutineContext().ensureActive()
                result
            } catch (error: CancellationException) {
                nativeSession.close()
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "MNN model load failed: ${model.id}", error)
                synchronized(nativeCallLock) {
                    if (lifecycleVersion == initializationVersion) {
                        _state.value = EngineState.Error(error.message ?: "MNN \u6a21\u578b\u52a0\u8f7d\u5931\u8d25")
                    }
                }
                false
            }

            val committed = synchronized(nativeCallLock) {
                if (lifecycleVersion != initializationVersion ||
                    _state.value != EngineState.Initializing
                ) {
                    false
                } else {
                    if (loaded) {
                        session = nativeSession
                        loadedModelId = model.id
                        loadedModel = model
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
            loaded
        }
    }

    override fun streamResponse(
        session: GenerationSession,
        prompt: String,
        history: List<ChatMessage>,
        params: GenerationParams
    ): Flow<String> = callbackFlow {
        val nativeSession = this@MnnLocalAiEngine.session
        val model = loadedModel
        if (nativeSession == null || model == null || _state.value != EngineState.Ready) {
            close(IllegalStateException("\u672c\u5730\u6a21\u578b\u5c1a\u672a\u5c31\u7eea"))
            return@callbackFlow
        }
        val payload = runCatching {
            promptAdapterRegistry.buildPayload(
                model = model,
                prompt = prompt,
                history = history,
                params = params
            )
        }.getOrElse { error ->
            close(error)
            return@callbackFlow
        }

        synchronized(nativeCallLock) {
            if (this@MnnLocalAiEngine.session !== nativeSession ||
                _state.value != EngineState.Ready
            ) {
                close()
                return@callbackFlow
            }
            activeSessionId = session.id
            activeNativeSession = nativeSession
        }
        val generationJob = launch(Dispatchers.IO) {
            generationMutex.withLock {
                var chunkCount = 0
                var outputCodePointCount = 0
                var firstChunkMs: Long? = null
                val generationStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    val shouldGenerate = synchronized(nativeCallLock) {
                        activeSessionId == session.id && activeNativeSession === nativeSession
                    }
                    if (shouldGenerate) {
                        nativeSession.generate(payload, params) { token ->
                            if (activeSessionId != session.id) {
                                false
                            } else {
                                val delta = sanitizeChunk(token)
                                if (delta.isNotEmpty()) {
                                    chunkCount += 1
                                    outputCodePointCount += delta.codePointCount(0, delta.length)
                                    if (firstChunkMs == null) {
                                        firstChunkMs = SystemClock.elapsedRealtime() - generationStartMs
                                    }
                                }
                                delta.isEmpty() || trySendBlocking(delta).isSuccess
                            }
                        }
                    }
                }.onFailure { error ->
                    val ownsActiveSession = synchronized(nativeCallLock) {
                        if (activeSessionId != session.id) {
                            false
                        } else {
                            activeSessionId = null
                            activeNativeSession = null
                            true
                        }
                    }
                    if (ownsActiveSession) {
                        _state.value = EngineState.Error(error.message ?: "MNN \u751f\u6210\u5931\u8d25")
                        Log.e(TAG, "MNN generation failed: session=${session.id}", error)
                        close(error)
                    } else {
                        close()
                    }
                }.onSuccess {
                    val elapsedMs = SystemClock.elapsedRealtime() - generationStartMs
                    val outputCodePointsPerSecond = if (elapsedMs > 0) {
                        outputCodePointCount * 1000.0 / elapsedMs
                    } else {
                        0.0
                    }
                    Log.i(
                        TAG,
                        "MNN generation finished: session=${session.id}, " +
                            "firstChunkMs=${firstChunkMs ?: -1}, chunks=$chunkCount, " +
                            "outputCodePoints=$outputCodePointCount, " +
                            "outputCodePointsPerSecond=$outputCodePointsPerSecond, elapsedMs=$elapsedMs"
                    )
                    synchronized(nativeCallLock) {
                        if (activeSessionId == session.id) {
                            activeSessionId = null
                            activeNativeSession = null
                        }
                    }
                    close()
                }
            }
        }

        awaitClose {
            val sessionToStop = synchronized(nativeCallLock) {
                if (activeSessionId != session.id) {
                    null
                } else {
                    activeSessionId = null
                    activeNativeSession = null
                    nativeSession
                }
            }
            sessionToStop?.stop()
            generationJob.cancel()
        }
    }

    override fun stopGeneration(session: GenerationSession) {
        val nativeSession = synchronized(nativeCallLock) {
            if (activeSessionId != session.id) {
                null
            } else {
                val activeSession = activeNativeSession
                activeSessionId = null
                activeNativeSession = null
                activeSession
            }
        }
        nativeSession?.stop()
    }

    override fun release() {
        val nativeSessionToStop = synchronized(nativeCallLock) {
            lifecycleVersion += 1
            _state.value = EngineState.Idle
            activeSessionId = null
            val activeSession = activeNativeSession
            activeNativeSession = null
            activeSession
        }
        nativeSessionToStop?.stop()
        synchronized(nativeCallLock) {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        session?.close()
        session = null
        loadedModelId = null
        loadedModel = null
    }

    private fun sanitizeChunk(rawChunk: String): String {
        return rawChunk.substringBefore(END_OF_PROMPT)
    }

    private companion object {
        const val TAG = "MnnLocalAiEngine"
        const val END_OF_PROMPT = "<eop>"
    }
}
