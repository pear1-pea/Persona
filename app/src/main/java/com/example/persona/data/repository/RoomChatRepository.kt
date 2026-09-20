package com.example.persona.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.mapper.toDomain
import com.example.persona.data.mapper.toEntity
import com.example.persona.data.mapper.toTraitEntities
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import com.example.persona.core.auth.AuthManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val authManager: AuthManager
) : ChatRepository {

    private val ownerId: String
        get() = authManager.currentRepoId

    override fun getMessagesStream(personaId: String): Flow<PagingData<Message>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20, 
                enablePlaceholders = false
            ),
            pagingSourceFactory = { messageDao.getMessagesByPersonaId(personaId, ownerId) }
        ).flow.map { pagingData ->
            pagingData.map { it.toDomain() }
        }
    }

    override suspend fun getRecentMessages(personaId: String, limit: Int): List<Message> {
        return messageDao.getRecentMessagesByPersonaId(personaId, ownerId, limit)
            .asReversed()
            .map { it.toDomain() }
    }

    override suspend fun saveMessage(message: Message, persona: Persona) {
        messageDao.insertPersonaAndMessage(
            persona = persona.toEntity(),
            traits = persona.toTraitEntities(),
            message = message.toEntity(ownerId)
        )
    }

    override suspend fun updateMessageContent(id: String, content: String, status: MessageStatus) {
        check(messageDao.updateMessageContent(id, ownerId, content, status.name) == 1) {
            "Message not found: $id"
        }
    }

    override suspend fun deleteMessagesForPersona(personaId: String) {
        messageDao.deleteMessagesByPersonaId(personaId, ownerId)
    }

    override suspend fun deleteMessagesForOwner(ownerId: String) {
        messageDao.deleteMessagesByOwnerId(ownerId)
    }
}
