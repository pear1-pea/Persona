package com.example.persona.domain.repository

import androidx.paging.PagingData
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessagesStream(personaId: String): Flow<PagingData<Message>>

    suspend fun saveMessage(message: Message, persona: Persona)

    suspend fun updateMessageContent(id: String, content: String)
}