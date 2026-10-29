package com.example.persona.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.persona.core.auth.AuthManager
import com.example.persona.data.local.dao.MessageDao
import com.example.persona.data.mapper.toDomain
import com.example.persona.data.mapper.toEntity
import com.example.persona.data.mapper.toTraitEntities
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val authManager: AuthManager
) : ChatRepository {
    override fun getMessagesStream(personaId: String): Flow<PagingData<Message>> {
        val identity = authManager.identity.value
        return channelFlow {
            authManager.withIdentity(identity) {
                Pager(
                    config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                    pagingSourceFactory = {
                        authManager.requireIdentity(identity)
                        messageDao.getMessagesByPersonaId(personaId, identity.ownerId)
                    }
                ).flow.map { pagingData -> pagingData.map { it.toDomain() } }.collect { send(it) }
            }
        }
    }

    override suspend fun getRecentMessages(personaId: String, limit: Int): List<Message> {
        val identity = authManager.requestIdentity()
        return messageDao.getRecentMessagesByPersonaId(personaId, identity.ownerId, limit)
            .asReversed().map { it.toDomain() }
            .also { authManager.requireIdentity(identity) }
    }

    override suspend fun saveMessage(message: Message, persona: Persona) = authManager.withLocalIdentity { owner ->
        require(message.personaId == persona.id)
        messageDao.insertPersonaAndMessage(
            persona = persona.toEntity(),
            traits = persona.toTraitEntities(),
            message = message.toEntity(owner)
        )
    }

    override suspend fun updateMessageContent(id: String, content: String, status: MessageStatus) =
        authManager.withLocalIdentity { owner ->
            check(messageDao.updateMessageContent(id, owner, content, status.name) == 1) {
                "Message not found: $id"
            }
        }

    override suspend fun deleteMessagesForPersona(personaId: String) = authManager.withLocalIdentity { owner ->
        messageDao.deleteMessagesByPersonaId(personaId, owner)
    }

    override suspend fun deleteMessagesForOwner(ownerId: String) {
        // Explicit owner cleanup is idempotent and never consults the next session.
        messageDao.deleteMessagesByOwnerId(ownerId)
    }
}
