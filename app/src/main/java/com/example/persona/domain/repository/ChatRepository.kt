package com.example.persona.domain.repository

import androidx.paging.PagingData
import com.example.persona.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessagesStream(personaId: String): Flow<PagingData<Message>>

    suspend fun saveMessage(message: Message)

    suspend fun updateMessageContent(id: String, content: String)
}