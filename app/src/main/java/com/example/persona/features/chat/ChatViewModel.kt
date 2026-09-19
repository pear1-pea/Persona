package com.example.persona.features.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.Backend
import com.example.persona.core.ai.EngineState
import com.example.persona.core.ai.GenerationError
import com.example.persona.core.ai.GenerationEvent
import com.example.persona.core.ai.GenerationSession
import com.example.persona.core.ai.PrivacyLevel
import com.example.persona.core.ai.Route
import com.example.persona.core.ai.TaskComplexity
import com.example.persona.core.base.BaseViewModel
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
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
    val lastRoutingDecision = hybridRepository.lastRoutingDecision

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

    fun sendMessage(userText: String, localOnly: Boolean = false) {
        val persona = _currentPersona.value ?: return
        val finalUserText = userText.trim()
        if (finalUserText.isEmpty()) return

        stopGenerating()
        val session = GenerationSession()
        activeGenerationSession = session
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            try {
                generateResponse(session, persona, finalUserText, localOnly)
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
        originalUserText: String,
        requestedLocalOnly: Boolean
    ) {
        val modeOverride = parseModeOverride(originalUserText)
        val localOnly = requestedLocalOnly || modeOverride.localOnly
        val forceCloud = modeOverride.forceCloud && !localOnly
        val userText = modeOverride.userText
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

        val systemPrompt = buildSystemPrompt(persona)
        var currentAiMessageId: String? = null
        var currentContent = StringBuilder()
        var currentBackend = Backend.CLOUD
        var persistedContent = ""
        var persistedStatus = MessageStatus.GENERATING
        var lastPersistMs = 0L
        var stoppedForRepetition = false
        var mode = HybridAiRepository.Mode.CLOUD
        var route = Route.CLOUD_ONLY
        var terminalStatus: MessageStatus? = null

        suspend fun persist(
            messageId: String,
            text: String,
            status: MessageStatus = MessageStatus.NORMAL,
            force: Boolean = false
        ) {
            if (!force && text == persistedContent && status == persistedStatus) return
            chatRepository.updateMessageContent(messageId, text, status)
            // Treat the in-memory value as committed only after Room succeeds.
            persistedContent = text
            persistedStatus = status
            lastPersistMs = System.currentTimeMillis()
        }

        suspend fun createAiMessage(backend: Backend): String {
            val messageId = UUID.randomUUID().toString()
            chatRepository.saveMessage(
                Message(
                    id = messageId,
                    personaId = persona.id,
                    content = PLACEHOLDER_THINKING,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis() + 1,
                    status = MessageStatus.GENERATING
                ),
                persona
            )
            currentAiMessageId = messageId
            currentBackend = backend
            currentContent = StringBuilder()
            persistedContent = PLACEHOLDER_THINKING
            persistedStatus = MessageStatus.GENERATING
            lastPersistMs = 0L
            return messageId
        }

        try {
            val routingDecision = hybridRepository.selectRouteForGeneration(
                forceCloud = forceCloud,
                localOnly = localOnly,
                privacyLevel = if (localOnly) PrivacyLevel.PRIVATE else PrivacyLevel.NORMAL,
                taskComplexity = inferTaskComplexity(userText)
            )
            route = routingDecision.route
            mode = if (routingDecision.isLocalPreferred) {
                HybridAiRepository.Mode.LOCAL
            } else {
                HybridAiRepository.Mode.CLOUD
            }
            _isCloudMode.value = mode == HybridAiRepository.Mode.CLOUD
            createAiMessage(if (mode == HybridAiRepository.Mode.LOCAL) Backend.MNN else Backend.CLOUD)

            hybridRepository.streamResponse(
                mode = mode,
                session = session,
                systemPrompt = systemPrompt,
                userMessage = userText,
                history = localHistory,
                route = route
            )
                .collect { event ->
                    if (activeGenerationSession?.id != session.id) return@collect
                    if (stoppedForRepetition) return@collect
                    val messageId = currentAiMessageId ?: return@collect
                    when (event) {
                        is GenerationEvent.Delta -> {
                            currentContent.append(event.text)
                            val text = currentContent.toString()
                            if (currentBackend == Backend.MNN && text.hasRepetitionLoop()) {
                                stoppedForRepetition = true
                                terminalStatus = MessageStatus.STOPPED
                                persist(
                                    messageId = messageId,
                                    text = text.trim().appendStopNotice(),
                                    status = MessageStatus.STOPPED,
                                    force = true
                                )
                                hybridRepository.stopGeneration(session)
                                return@collect
                            }
                            if (System.currentTimeMillis() - lastPersistMs >= PERSIST_INTERVAL_MS) {
                                persist(messageId, text, status = MessageStatus.GENERATING)
                            }
                        }

                        is GenerationEvent.Completed -> {
                            if (terminalStatus == null) {
                                val text = currentContent.toString()
                                if (text.isBlank()) {
                                    terminalStatus = MessageStatus.FAILED
                                    persist(
                                        messageId = messageId,
                                        text = EMPTY_RESPONSE_MESSAGE,
                                        status = MessageStatus.FAILED,
                                        force = true
                                    )
                                } else {
                                    terminalStatus = MessageStatus.NORMAL
                                    persist(messageId, text, status = MessageStatus.NORMAL, force = true)
                                }
                            }
                        }

                        is GenerationEvent.Stopped -> {
                            terminalStatus = MessageStatus.STOPPED
                            val text = event.partialText.ifBlank {
                                currentContent.toString().ifBlank { STOPPED_RESPONSE_MESSAGE }
                            }
                            persist(
                                messageId = messageId,
                                text = text,
                                status = MessageStatus.STOPPED,
                                force = true
                            )
                        }

                        is GenerationEvent.Failed -> {
                            val failedText = failureDisplayText(event.error, event.partialText)
                            persist(
                                messageId = messageId,
                                text = failedText,
                                status = MessageStatus.FAILED,
                                force = true
                            )
                            terminalStatus = MessageStatus.FAILED
                            if (
                                event.backend == Backend.MNN &&
                                mode == HybridAiRepository.Mode.LOCAL &&
                                route == Route.LOCAL_THEN_CLOUD
                            ) {
                                emitError("本地 AI 生成失败，已切换到云端。")
                                _isCloudMode.value = true
                                terminalStatus = null
                                createAiMessage(Backend.CLOUD)
                            } else {
                                val notice = if (route == Route.LOCAL) {
                                    "本地 AI 生成失败，隐私策略未切换到云端。"
                                } else {
                                    "生成失败: ${event.error.message}"
                                }
                                emitError(notice)
                            }
                        }
                    }
                }

            currentAiMessageId?.let { messageId ->
                if (!stoppedForRepetition && terminalStatus == null) {
                    if (currentContent.isEmpty()) {
                        if (activeGenerationSession?.id == session.id) {
                            persist(
                                messageId = messageId,
                                text = EMPTY_RESPONSE_MESSAGE,
                                status = MessageStatus.FAILED,
                                force = true
                            )
                        }
                    } else {
                        persist(messageId, currentContent.toString(), status = MessageStatus.NORMAL, force = true)
                    }
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    val stoppedContent = when {
                        stoppedForRepetition -> currentContent.toString().trim().appendStopNotice()
                        currentContent.isEmpty() -> STOPPED_RESPONSE_MESSAGE
                        else -> currentContent.toString()
                    }
                    currentAiMessageId?.let { messageId ->
                        persist(
                            messageId = messageId,
                            text = stoppedContent,
                            status = MessageStatus.STOPPED,
                            force = true
                        )
                    }
                }.onFailure { persistError ->
                    Log.e(TAG, "Failed to persist stopped generation", persistError)
                }
            }
            throw error
        } catch (error: Throwable) {
            hybridRepository.stopGeneration(session)
            Log.e(TAG, "Generation or persistence failed", error)
            val reason = error.describeForUser(currentBackend)
            val failureContent = currentContent.toString()
                .failureDisplayText(reason)
            withContext(NonCancellable) {
                runCatching {
                    currentAiMessageId?.let { messageId ->
                        persist(messageId, failureContent, status = MessageStatus.FAILED, force = true)
                    }
                }.onFailure { persistError ->
                    Log.e(TAG, "Failed to persist generation failure", persistError)
                }
            }
            emitError("生成失败: $reason")
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
                message.status == MessageStatus.NORMAL &&
                content.length <= MAX_HISTORY_MESSAGE_CHARS &&
                content !in HISTORY_EXCLUDED_MESSAGES &&
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

    private fun Throwable.describeForUser(backend: Backend): String {
        val source = if (backend == Backend.MNN) "本地 AI" else "云端 AI"
        val detail = message?.takeIf(String::isNotBlank) ?: return "$source 生成失败，请稍后再试。"
        return "$source 错误: $detail"
    }

    private fun failureDisplayText(error: GenerationError, partialText: String): String {
        return partialText.failureDisplayText(error.message)
    }

    private fun String.failureDisplayText(reason: String): String {
        return if (isBlank()) {
            reason
        } else {
            this + "\n\n" + reason
        }
    }

    private data class ModeOverride(
        val forceCloud: Boolean,
        val localOnly: Boolean,
        val userText: String
    )

    private fun parseModeOverride(text: String): ModeOverride {
        val trimmed = text.trim()
        val forceCloud = trimmed.startsWith(CLOUD_PREFIX, ignoreCase = true)
        val localOnly = trimmed.startsWith(LOCAL_PREFIX, ignoreCase = true)
        return when {
            forceCloud -> ModeOverride(
                forceCloud = true,
                localOnly = false,
                userText = trimmed.drop(CLOUD_PREFIX.length).trimStart()
            )
            localOnly -> ModeOverride(
                forceCloud = false,
                localOnly = true,
                userText = trimmed.drop(LOCAL_PREFIX.length).trimStart()
            )
            else -> ModeOverride(false, false, trimmed)
        }
    }

    private fun inferTaskComplexity(text: String): TaskComplexity {
        val normalized = text.lowercase()
        if (text.length > COMPLEX_TASK_LENGTH ||
            normalized.contains("```") ||
            COMPLEX_TASK_TERMS.any { term -> normalized.contains(term) }
        ) {
            return TaskComplexity.COMPLEX
        }
        if (text.length <= SIMPLE_TASK_LENGTH) return TaskComplexity.SIMPLE
        return TaskComplexity.NORMAL
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val LOCAL_HISTORY_LIMIT = 12
        const val PERSIST_INTERVAL_MS = 100L
        const val PLACEHOLDER_THINKING = "正在思考..."
        const val STOPPED_RESPONSE_MESSAGE = "已停止生成"
        const val EMPTY_RESPONSE_MESSAGE = "未收到回复，请重试。"
        const val REPETITION_STOPPED_MESSAGE = "已停止重复输出。"
        const val CLOUD_GENERIC_ERROR = "云端 AI 生成失败，请稍后再试。"
        const val CLOUD_PREFIX = "@cloud"
        const val LOCAL_PREFIX = "@local"
        const val MAX_HISTORY_MESSAGE_CHARS = 1500
        const val MIN_REPEAT_SCAN_CHARS = 36
        const val MIN_REPEAT_SEGMENT_CHARS = 12
        const val MAX_REPEAT_SEGMENT_CHARS = 80
        const val MIN_REPEAT_SENTENCE_CHARS = 6
        const val REPEAT_COUNT = 3
        const val SIMPLE_TASK_LENGTH = 40
        const val COMPLEX_TASK_LENGTH = 1200
        val COMPLEX_TASK_TERMS = setOf(
            "代码",
            "debug",
            "架构",
            "证明",
            "分析",
            "比较",
            "refactor",
            "implement"
        )
        val HISTORY_EXCLUDED_MESSAGES = setOf(
            PLACEHOLDER_THINKING,
            STOPPED_RESPONSE_MESSAGE,
            EMPTY_RESPONSE_MESSAGE,
            REPETITION_STOPPED_MESSAGE
        )
    }
}
