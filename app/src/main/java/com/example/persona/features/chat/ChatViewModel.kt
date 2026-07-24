package com.example.persona.features.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.persona.core.base.BaseViewModel
import com.example.persona.data.repository.HybridAiRepository
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val hybridRepository: HybridAiRepository,
    private val personaRepository: PersonaRepository,
    private val chatRepository: ChatRepository
) : BaseViewModel() {

    init {
        launchCatching(block = {
            hybridRepository.initEdgeModel()
            Log.d("ChatViewModel", "Edge model initialized")
        })
    }

    // State: cloud mode indicator
    private val _isCloudMode = MutableStateFlow(false)
    val isCloudMode = _isCloudMode.asStateFlow()

    // State: current chat persona
    private val _currentPersona = MutableStateFlow<Persona?>(null)
    val currentPersona = _currentPersona.asStateFlow()

    // Paging data flow
    // Observe _currentPersona; when set, request paged data for that persona ID from repository
    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesFlow: Flow<PagingData<Message>> = _currentPersona.flatMapLatest { persona ->
        if (persona != null) {
            chatRepository.getMessagesStream(persona.id)
                .cachedIn(viewModelScope)
        } else {
            emptyFlow()
        }
    }

    fun loadPersonaInfo(id: String) {
        launchCatching(block = {
            _currentPersona.value = personaRepository.getPersonaById(id)
        })
    }

    fun sendMessage(userText: String,  isSymbiosis: Boolean) {
        val persona = _currentPersona.value ?: return
        val personaId = persona.id

        var finalUserText = userText.trim()
        val forceCloud = finalUserText.startsWith("@cloud", ignoreCase = true)
        if (forceCloud) {
            finalUserText = finalUserText.removePrefix("@cloud").removePrefix("@Cloud").trim()
        }

        launchCatching(block = {
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = Message(
                id = userMsgId,
                personaId = personaId,
                content = finalUserText,
                isFromUser = true,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.saveMessage(userMsg, persona)

            val aiMsgId = UUID.randomUUID().toString()
            val aiMsg = Message(
                id = aiMsgId,
                personaId = personaId,
                content = "Thinking...",
                isFromUser = false,
                timestamp = System.currentTimeMillis() + 1 )
            chatRepository.saveMessage(aiMsg, persona)

            // Intelligent routing logic
            val mode = if (forceCloud || !isSymbiosis) {
                HybridAiRepository.Mode.CLOUD
            } else {
                // On-device complexity evaluation
                val score = hybridRepository.evaluateComplexity(finalUserText)
                if (score > 0.5f) {
                    HybridAiRepository.Mode.CLOUD
                } else {
                    HybridAiRepository.Mode.EDGE
                }
            }
            _isCloudMode.value = (mode == HybridAiRepository.Mode.CLOUD)



            // Prepare prompt
            val systemPrompt = if (mode == HybridAiRepository.Mode.EDGE) {
                "You are ${persona.name}. ${persona.traits.joinToString()}."
            } else {
                "You are ${persona.name}. ${persona.backstory}. Traits: ${persona.traits.joinToString()}. Reply in the user's language."
            }

            // Stream response and update database
            var currentContent = ""
            var isFirstToken = true

            hybridRepository.streamResponse(mode, systemPrompt, finalUserText)
                .catch { e ->
                    // On error, update database state
                    Log.e("ChatViewModel", "Stream error", e)
                    val errorSource = if (mode == HybridAiRepository.Mode.EDGE) "Edge AI" else "Cloud API"
                    chatRepository.updateMessageContent(aiMsgId, "[$errorSource Error] ${e.message}")
                    emitError("生成失败: ${e.message}") 
                }
                .collect { token ->
                    if (isFirstToken) {
                        currentContent = token
                        isFirstToken = false
                    } else {
                        currentContent += token
                    }

                    chatRepository.updateMessageContent(aiMsgId, currentContent)
                }
        })
    }
}