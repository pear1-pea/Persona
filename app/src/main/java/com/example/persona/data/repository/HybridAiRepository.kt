package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
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
        localModelManager.refreshInstalledModels()
        val model = localModelManager.currentModel.value ?: run {
            releaseLocalRoute()
            return false
        }
        return initializeSelectedModel(model)
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
    ): Flow<String> = when (mode) {
        Mode.CLOUD -> flow {
            _activeMode.value = Mode.CLOUD
            emitAll(cloudRepository.streamResponse(systemPrompt, userMessage, history))
        }
        Mode.LOCAL -> flow {
            _activeMode.value = Mode.LOCAL
            synchronized(routeLock) {
                activeGenerationSession = session
            }
            var emittedLocalToken = false
            try {
                localAiEngine.streamResponse(
                    session = session,
                    prompt = userMessage,
                    history = listOf(ChatMessage("system", systemPrompt)) + history
                ).collect { token ->
                    emittedLocalToken = true
                    emit(token)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (emittedLocalToken) {
                    emit(LOCAL_FALLBACK_NOTICE)
                }
                Log.e(TAG, "Local generation failed; falling back to cloud", error)
                _activeMode.value = Mode.CLOUD
                emitAll(cloudRepository.streamResponse(systemPrompt, userMessage, history))
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
        var model = localModelManager.currentModel.value
        if (model == null) {
            localModelManager.refreshInstalledModels()
            model = localModelManager.currentModel.value
        }

        if (model == null) {
            releaseLocalRoute()
            return false
        }

        if (localAiEngine.state.value == EngineState.Ready && loadedModelDir == model.modelDir) {
            _activeMode.value = Mode.LOCAL
            return true
        }

        if (loadedModelDir != null && loadedModelDir != model.modelDir) {
            releaseLocalRoute()
        }

        return initializeSelectedModel(model)
    }

    private suspend fun initializeSelectedModel(model: InstalledModel): Boolean {
        selectedModelDir = model.modelDir
        val initialized = runCatching {
            localAiEngine.initialize(model)
        }.getOrElse { error ->
            Log.e(TAG, "Local model initialization failed: ${model.id}", error)
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
            return false
        }

        if (initialized) {
            loadedModelDir = model.modelDir
            _activeMode.value = Mode.LOCAL
        } else {
            loadedModelDir = null
            _activeMode.value = Mode.CLOUD
        }
        return initialized
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
        const val LOCAL_FALLBACK_NOTICE = "\n\n[本地 AI 生成中断，已切换到云端继续。]\n"
    }
}
