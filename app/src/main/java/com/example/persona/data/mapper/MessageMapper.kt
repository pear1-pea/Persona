package com.example.persona.data.mapper

import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.domain.model.Message

fun MessageEntity.toDomain(): Message {
    return Message(
        id = id,
        personaId = personaId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        personaId = personaId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp
    )
}