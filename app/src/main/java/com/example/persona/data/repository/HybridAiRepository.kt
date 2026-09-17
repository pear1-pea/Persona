package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationErrorType
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.LocalModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAiRepository @Inject constructor(
    private val cloudRepository: CloudChatRepository,
    private val localAiEngine: LocalAiEngine,
    private val localModelManager: LocalModelManager
) {
    enum class Mode { CLOUD, LOCAL }

    val localEngineState = localAiEngine.state
    private val _activeMode = MutableStateFlow(Mode.CLOUD)
    val activeMode = _activeMode.asStateFlow()

    private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routeLock = Any()
    private val localInitializationMutex = Mutex()

    @Volatile
    private var selectedModelDir: String? = localModelManager.currentModel.value?.modelDir

    @Volatile
    private var loadedModelDir: String? = null

    @Volatile
    private var activeGenerationSession: GenerationSession? = null

    init {
        routeScope.launch {
            try {
                localModelManager.currentModel.collect { model ->
                    handleCurrentModelChanged(model)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to observe current model changes", error)
            }
        }
    }

    suspend fun initializeLocalModel(): Boolean {
        return localInitializationMutex.withLock {
            localModelManager.refreshInstalledModels()
            val model = localModelManager.currentModel.value ?: run {
                releaseLocalRoute()
                return@withLock false
            }
            initializeSelectedModel(model)
        }
    }

    fun selectMode(forceCloud: Boolean, localEnabled: Boolean = true): Mode {
        return if (!forceCloud &&
            localEnabled &&
            localModelManager.currentModel.value != null &&
            localAiEngine.state.value == EngineState.Ready &&
            loadedModelDir == localModelManager.currentModel.value?.modelDir
        ) {
            Mode.LOCAL
        } else {
            Mode.CLOUD
        }
    }

    suspend fun selectModeForGeneration(forceCloud: Boolean, localEnabled: Boolean = true): Mode {
        if (forceCloud || !localEnabled) {
            _activeMode.value = Mode.CLOUD
            return Mode.CLOUD
        }

        return if (ensureLocalReady()) {
            Mode.LOCAL
        } else {
            Mode.CLOUD
        }
    }

    fun stopGeneration(session: GenerationSession) {
        localAiEngine.stopGeneration(session)
        synchronized(routeLock) {
            if (activeGenerationSession?.id == session.id) {
                activeGenerationSession = null
            }
        }
    }

    fun streamResponse(
        mode: Mode,
        session: GenerationSession,
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage> = emptyList()
    ): Flow<GenerationEvent> = when (mode) {
        Mode.CLOUD -> flow {
            _activeMode.value = Mode.CLOUD
            emitAll(cloudRepository.streamResponse(systemPrompt, userMessage, history))
        }
        Mode.LOCAL -> flow {
            _activeMode.value = Mode.LOCAL
            synchronized(routeLock) {
                activeGenerationSession = session
            }
            try {
                val localPartial = StringBuilder()
                var localTerminal = false
                val localFailure = try {
                    var failure: GenerationEvent.Failed? = null
                    localAiEngine.streamResponse(
                        session = session,
                        prompt = userMessage,
                        history = listOf(ChatMessage("system", systemPrompt)) + history
                    ).collect { event ->
                        if (localTerminal) return@collect
                        when (event) {
                            is GenerationEvent.Delta -> localPartial.append(event.text)
                            is GenerationEvent.Failed -> {
                                failure = event
                                localTerminal = true
                            }
                            is GenerationEvent.Completed,
                            is GenerationEvent.Stopped -> localTerminal = true
                        }
                        if (event !is GenerationEvent.Failed) {
                            emit(event)
                        }
                    }
                    failure ?: if (!localTerminal) {
                        GenerationEvent.Failed(
                            backend = Backend.MNN,
                            error = GenerationError(
                                type = GenerationErrorType.MODEL_RUNTIME,
                                message = "本地 AI 流提前结束"
                            ),
                            partialText = localPartial.toString()
                        )
                    } else {
                        null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Local generation failed; falling back to cloud", error)
                    GenerationEvent.Failed(
                        backend = Backend.MNN,
                        error = GenerationError(
                            type = GenerationErrorType.MODEL_RUNTIME,
                            message = error.message ?: "本地 AI 生成失败",
                            cause = error
                        ),
                        partialText = localPartial.toString()
                    )
                }
                if (localFailure != null) {
                    Log.e(TAG, "Local generation failed; falling back to cloud")
                    localModelManager.currentModel.value?.let { model ->
                        localModelManager.recordLocalLoadFailure(
                            model,
                            localFailure.error.cause ?: IllegalStateException(localFailure.error.message)
                        )
                    }
                    _activeMode.value = Mode.CLOUD
                    emit(localFailure)
                    emitAll(cloudRepository.streamResponse(systemPrompt, userMessage, history))
                }
            } finally {
                synchronized(routeLock) {
                    if (activeGenerationSession?.id == session.id) {
                        activeGenerationSession = null
                    }
                }
            }
        }
    }

    private suspend fun ensureLocalReady(): Boolean {
        return localInitializationMutex.withLock {
            var model = localModelManager.currentModel.value
            if (model == null) {
                localModelManager.refreshInstalledModels()
                model = localModelManager.currentModel.value
            }

            if (model == null) {
                releaseLocalRoute()
                return@withLock false
            }

            if (localAiEngine.state.value == EngineState.Ready && loadedModelDir == model.modelDir) {
                _activeMode.value = Mode.LOCAL
                return@withLock true
            }

            if (loadedModelDir != null && loadedModelDir != model.modelDir) {
                releaseLocalRoute()
            }

            initializeSelectedModel(model)
        }
    }

    private suspend fun initializeSelectedModel(model: InstalledModel): Boolean {
        if (localModelManager.recentFailureCount(model.id) > 0) {
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
            return false
        }
        selectedModelDir = model.modelDir
        val initialized = try {
            localAiEngine.initialize(model)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Local model initialization failed: ${model.id}", error)
            localModelManager.recordLocalLoadFailure(model, error)
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
            return false
        }

        if (!initialized) {
            val reason = (localAiEngine.state.value as? EngineState.Error)?.reason
            localModelManager.recordLocalLoadFailure(
                model,
                reason?.let { IllegalStateException(it) }
            )
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
            return false
        }

        val smokePassed = try {
            localAiEngine.smokeTest(model)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Local model smoke test failed: ${model.id}", error)
            false
        }

        if (smokePassed) {
            localModelManager.recordLocalLoadSuccess(model)
            loadedModelDir = model.modelDir
            _activeMode.value = Mode.LOCAL
        } else {
            localAiEngine.release()
            localModelManager.recordLocalLoadFailure(
                model,
                IllegalStateException("本地模型 smoke test 失败")
            )
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
        }
        return smokePassed
    }

    private fun handleCurrentModelChanged(model: InstalledModel?) {
        val newModelDir = model?.modelDir
        synchronized(routeLock) {
            if (newModelDir == selectedModelDir) return
            selectedModelDir = newModelDir
        }

        val shouldRelease = loadedModelDir != null ||
            activeGenerationSession != null ||
            localAiEngine.state.value == EngineState.Ready

        if (shouldRelease) {
            releaseLocalRoute()
        } else {
            _activeMode.value = Mode.CLOUD
        }
    }

    private fun releaseLocalRoute() {
        val sessionToStop = synchronized(routeLock) {
            val session = activeGenerationSession
            activeGenerationSession = null
            loadedModelDir = null
            session
        }
        sessionToStop?.let(localAiEngine::stopGeneration)
        localAiEngine.release()
        _activeMode.value = Mode.CLOUD
    }

    private companion object {
        const val TAG = "HybridAiRepository"
    }
}
