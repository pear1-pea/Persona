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

    fun clearCurrentConversation() {
        val persona = _currentPersona.value ?: return
        stopGenerating()
        launchCatching(block = {
            chatRepository.deleteMessagesForPersona(persona.id)
            emitError("已清空当前对话")
        })
    }

    private suspend fun generateResponse(
        session: GenerationSession,
        persona: Persona,
        originalUserText: String
    ) {
        val (forceCloud, userText) = parseModeOverride(originalUserText)
        if (userText.isEmpty()) return

        val localHistory = chatRepository.getRecentMessages(persona.id, LOCAL_HISTORY_LIMIT)
            .filterStableHistory()
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

        val mode = hybridRepository.selectModeForGeneration(forceCloud)
        _isCloudMode.value = mode == HybridAiRepository.Mode.CLOUD
        val systemPrompt = buildSystemPrompt(persona)

        var content = ""
        var streamFailed = false
        var stoppedForRepetition = false
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
                    if (stoppedForRepetition) return@collect
                    content += token
                    if (mode == HybridAiRepository.Mode.LOCAL && content.hasRepetitionLoop()) {
                        stoppedForRepetition = true
                        val cleanedContent = content.trim().appendStopNotice()
                        chatRepository.updateMessageContent(aiMessageId, cleanedContent)
                        hybridRepository.stopGeneration(session)
                        return@collect
                    } else {
                        chatRepository.updateMessageContent(aiMessageId, content)
                    }
                }

            if (!streamFailed && !stoppedForRepetition && content.isEmpty() && activeGenerationSession?.id == session.id) {
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

    private fun buildSystemPrompt(persona: Persona): String {
        return """
            You are ${persona.name}.
            Persona background: ${persona.backstory}
            Traits: ${persona.traits.joinToString()}

            Rules:
            - Reply in the user's language.
            - Keep replies concise and conversational.
            - Do not repeat the same sentence, phrase, or paragraph.
            - If the user greets you, answer naturally in 1-3 sentences.
            - Stop once you have answered the user's message.
        """.trimIndent()
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

    private fun List<Message>.filterStableHistory(): List<Message> {
        return filter { message ->
            val content = message.content.trim()
            content.isNotBlank() &&
                content.length <= MAX_HISTORY_MESSAGE_CHARS &&
                content !in HISTORY_EXCLUDED_MESSAGES &&
                !content.contains(LOCAL_FALLBACK_NOTICE_MARKER) &&
                !content.hasRepetitionLoop()
        }
    }

    private fun String.hasRepetitionLoop(): Boolean {
        val normalized = replace(Regex("\\s+"), "")
        if (normalized.length < MIN_REPEAT_SCAN_CHARS) return false

        if (hasRepeatedTail(normalized, MIN_REPEAT_SEGMENT_CHARS, MAX_REPEAT_SEGMENT_CHARS)) {
            return true
        }

        val sentences = split(Regex("(?<=[。！？.!?])"))
            .map { it.trim() }
            .filter { it.length >= MIN_REPEAT_SENTENCE_CHARS }
            .takeLast(6)
        return sentences.size >= 3 &&
            sentences.windowed(3).any { window -> window.distinct().size == 1 }
    }

    private fun hasRepeatedTail(text: String, minSegmentLength: Int, maxSegmentLength: Int): Boolean {
        val maxLength = minOf(maxSegmentLength, text.length / REPEAT_COUNT)
        if (maxLength < minSegmentLength) return false

        for (length in minSegmentLength..maxLength) {
            val tail = text.takeLast(length)
            if (tail.repeat(REPEAT_COUNT) == text.takeLast(length * REPEAT_COUNT)) {
                return true
            }
        }
        return false
    }

    private fun String.appendStopNotice(): String {
        return if (isBlank()) {
            REPETITION_STOPPED_MESSAGE
        } else {
            this + "\n\n" + REPETITION_STOPPED_MESSAGE
        }
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
        const val REPETITION_STOPPED_MESSAGE = "已停止重复输出。"
        const val LOCAL_FALLBACK_NOTICE_MARKER = "本地 AI 生成中断"
        const val CLOUD_PREFIX = "@cloud"
        const val MAX_HISTORY_MESSAGE_CHARS = 1500
        const val MIN_REPEAT_SCAN_CHARS = 36
        const val MIN_REPEAT_SEGMENT_CHARS = 12
        const val MAX_REPEAT_SEGMENT_CHARS = 80
        const val MIN_REPEAT_SENTENCE_CHARS = 6
        const val REPEAT_COUNT = 3
        val HISTORY_EXCLUDED_MESSAGES = setOf(
            PLACEHOLDER_THINKING,
            STOPPED_RESPONSE_MESSAGE,
            EMPTY_RESPONSE_MESSAGE,
            REPETITION_STOPPED_MESSAGE
        )
    }
}
