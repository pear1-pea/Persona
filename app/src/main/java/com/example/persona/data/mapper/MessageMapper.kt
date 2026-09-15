package com.example.persona.data.mapper

import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus

fun MessageEntity.toDomain(): Message {
    return Message(
        id = id,
        personaId = personaId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
        status = runCatching { MessageStatus.valueOf(status) }
            .getOrDefault(MessageStatus.NORMAL)
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        personaId = personaId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
        status = status.name
    )
}
