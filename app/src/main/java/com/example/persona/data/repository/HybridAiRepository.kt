package com.example.persona.data.repository

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.LocalAiEngine
import com.example.persona.core.ai.LocalModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    suspend fun initializeLocalModel(): Boolean {
        localModelManager.refreshInstalledModels()
        val model = localModelManager.currentModel.value ?: run {
            _activeMode.value = Mode.CLOUD
            localAiEngine.release()
            return false
        }
        val initialized = localAiEngine.initialize(model)
        _activeMode.value = if (initialized) Mode.LOCAL else Mode.CLOUD
        return initialized
    }

    fun selectMode(forceCloud: Boolean, localEnabled: Boolean = true): Mode {
        return if (!forceCloud &&
            localEnabled &&
            localModelManager.currentModel.value != null &&
            localAiEngine.state.value == EngineState.Ready
        ) {
            Mode.LOCAL
        } else {
            Mode.CLOUD
        }
    }

    fun stopGeneration(session: GenerationSession) {
        localAiEngine.stopGeneration(session)
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
                _activeMode.value = Mode.CLOUD
                emitAll(cloudRepository.streamResponse(systemPrompt, userMessage, history))
            }
        }
    }

    private companion object {
        const val LOCAL_FALLBACK_NOTICE = "\n\n[本地 AI 生成中断，已切换到云端继续。]\n"
    }
}
