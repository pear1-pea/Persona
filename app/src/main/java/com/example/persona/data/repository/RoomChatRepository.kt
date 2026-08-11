package com.example.persona.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.mapper.toDomain
import com.example.persona.data.mapper.toEntity
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatRepository @Inject constructor(
    private val messageDao: MessageDao
) : ChatRepository {

    override fun getMessagesStream(personaId: String): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20, 
                enablePlaceholders = false
            ),
            pagingSourceFactory = { messageDao.getMessagesByPersonaId(personaId) }
        ).flow.map { pagingData ->
            pagingData.map { it.toDomain() }
        }
    }

    override suspend fun getRecentMessages(personaId: String, limit: Int): List<Message> {
        return messageDao.getRecentMessagesByPersonaId(personaId, limit)
            .asReversed()
            .map { it.toDomain() }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun saveMessage(message: Message, persona: Persona) {
        messageDao.insertMessage(message.toEntity())
    }

    override suspend fun updateMessageContent(id: String, content: String) {
        val entity = messageDao.getMessageById(id)
        if (entity != null) {
            val updated = entity.copy(content = content)
            messageDao.updateMessage(updated)
        }
    }

    override suspend fun deleteMessagesForPersona(personaId: String) {
        messageDao.deleteMessagesByPersonaId(personaId)
    }
}
