package com.example.persona.features.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.base.BaseViewModel
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val hybridRepository: HybridAiRepository,
    private val personaRepository: PersonaRepository,
    private val chatRepository: ChatRepository
) : BaseViewModel() {

    private var activeGenerationSession: GenerationSession? = null
    private var generationJob: Job? = null

    private val _isCloudMode = MutableStateFlow(true)
    val isCloudMode = _isCloudMode.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    val localEngineState = hybridRepository.localEngineState

    init {
        viewModelScope.launch {
            hybridRepository.activeMode.collect { mode ->
                _isCloudMode.value = mode == HybridAiRepository.Mode.CLOUD
            }
        }
        viewModelScope.launch {
            localEngineState.collect { state ->
                if (state is EngineState.Error) {
                    _isCloudMode.value = true
                }
            }
        }
        launchCatching(block = {
            val initialized = hybridRepository.initializeLocalModel()
            _isCloudMode.value = !initialized
        })
    }

    private val _currentPersona = MutableStateFlow<Persona?>(null)
    val currentPersona = _currentPersona.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesFlow: Flow<PagingData<Message>> = _currentPersona.flatMapLatest { persona ->
        if (persona != null) {
            chatRepository.getMessagesStream(persona.id).cachedIn(viewModelScope)
        } else {
            emptyFlow()
        }
    }

    fun loadPersonaInfo(id: String) {
        launchCatching(block = {
            _currentPersona.value = personaRepository.getPersonaById(id)
        })
    }

    fun sendMessage(userText: String) {
        val persona = _currentPersona.value ?: return
        val finalUserText = userText.trim()
        if (finalUserText.isEmpty()) return

        stopGenerating()
        val session = GenerationSession()
        activeGenerationSession = session
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            try {
                generateResponse(session, persona, finalUserText)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Generation failed", error)
                emitError("生成失败: ${error.message}")
            } finally {
                finishGeneration(session)
            }
        }
    }

    fun stopGenerating() {
        val session = activeGenerationSession ?: return
        hybridRepository.stopGeneration(session)
        generationJob?.cancel()
        generationJob = null
        activeGenerationSession = null
        _isGenerating.value = false
    }

    private suspend fun generateResponse(
        session: GenerationSession,
        persona: Persona,
        originalUserText: String
    ) {
        val (forceCloud, userText) = parseModeOverride(originalUserText)
        if (userText.isEmpty()) return

        val localHistory = chatRepository.getRecentMessages(persona.id, LOCAL_HISTORY_LIMIT)
            .toLocalChatHistory()

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            personaId = persona.id,
            content = userText,
            isFromUser = true
        )
        chatRepository.saveMessage(userMessage, persona)

        val aiMessageId = UUID.randomUUID().toString()
        chatRepository.saveMessage(
            Message(
                id = aiMessageId,
                personaId = persona.id,
                content = PLACEHOLDER_THINKING,
                isFromUser = false,
                timestamp = System.currentTimeMillis() + 1
            ),
            persona
        )

        val mode = hybridRepository.selectMode(forceCloud)
        _isCloudMode.value = mode == HybridAiRepository.Mode.CLOUD
        val systemPrompt = "You are ${persona.name}. ${persona.backstory}. " +
            "Traits: ${persona.traits.joinToString()}. Reply in the user's language."

        var content = ""
        var streamFailed = false
        try {
            hybridRepository.streamResponse(mode, session, systemPrompt, userText, localHistory)
                .catch { error ->
                    if (error is CancellationException) throw error
                    streamFailed = true
                    Log.e(TAG, "Stream error", error)
                    val source = if (mode == HybridAiRepository.Mode.LOCAL) "本地 AI" else "云端 AI"
                    chatRepository.updateMessageContent(aiMessageId, "[$source 错误] ${error.message}")
                    emitError("生成失败: ${error.message}")
                }
                .collect { token ->
                    if (activeGenerationSession?.id != session.id) return@collect
                    content += token
                    chatRepository.updateMessageContent(aiMessageId, content)
                }

            if (!streamFailed && content.isEmpty() && activeGenerationSession?.id == session.id) {
                chatRepository.updateMessageContent(aiMessageId, EMPTY_RESPONSE_MESSAGE)
            }
        } catch (error: CancellationException) {
            if (content.isEmpty()) {
                withContext(NonCancellable) {
                    chatRepository.updateMessageContent(aiMessageId, STOPPED_RESPONSE_MESSAGE)
                }
            }
            throw error
        }

    }

    private fun finishGeneration(session: GenerationSession) {
        if (activeGenerationSession?.id == session.id) {
            activeGenerationSession = null
            generationJob = null
            _isGenerating.value = false
        }
    }

    override fun onCleared() {
        stopGenerating()
        super.onCleared()
    }

    private fun List<Message>.toLocalChatHistory(): List<ChatMessage> {
        return asSequence()
            .filter { it.content.isNotBlank() && it.content != PLACEHOLDER_THINKING }
            .map { message ->
                ChatMessage(
                    role = if (message.isFromUser) "user" else "assistant",
                    content = message.content
                )
            }
            .toList()
    }

    private fun parseModeOverride(text: String): Pair<Boolean, String> {
        val trimmed = text.trim()
        val forceCloud = trimmed.startsWith(CLOUD_PREFIX, ignoreCase = true)
        return if (forceCloud) {
            true to trimmed.drop(CLOUD_PREFIX.length).trimStart()
        } else {
            false to trimmed
        }
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val LOCAL_HISTORY_LIMIT = 12
        const val PLACEHOLDER_THINKING = "正在思考..."
        const val STOPPED_RESPONSE_MESSAGE = "已停止生成"
        const val EMPTY_RESPONSE_MESSAGE = "未收到回复，请重试。"
        const val CLOUD_PREFIX = "@cloud"
    }
}
