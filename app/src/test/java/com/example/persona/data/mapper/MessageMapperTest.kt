package com.example.persona.data.mapper

import com.example.persona.data.local.entity.MessageEntity
import com.example.persona.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMapperTest {

    private val entity = MessageEntity(
        id = "msg-1",
        personaId = "persona-1",
        content = "Hello, world!",
        isFromUser = true,
        timestamp = 1000L
    )

    @Test
    fun `toDomain maps all fields correctly`() {
        val domain = entity.toDomain()

        assertEquals(entity.id, domain.id)
        assertEquals(entity.personaId, domain.personaId)
        assertEquals(entity.content, domain.content)
        assertEquals(entity.isFromUser, domain.isFromUser)
        assertEquals(entity.timestamp, domain.timestamp)
    }

    @Test
    fun `toEntity maps all fields correctly`() {
        val domain = Message(
            id = "msg-2",
            personaId = "persona-2",
            content = "Hi back!",
            isFromUser = false,
            timestamp = 2000L
        )
        val result = domain.toEntity()

        assertEquals(domain.id, result.id)
        assertEquals(domain.personaId, result.personaId)
        assertEquals(domain.content, result.content)
        assertEquals(domain.isFromUser, result.isFromUser)
        assertEquals(domain.timestamp, result.timestamp)
    }

    @Test
    fun `round-trip toDomain then toEntity preserves data`() {
        val domain = entity.toDomain()
        val back = domain.toEntity()

        assertEquals(entity.id, back.id)
        assertEquals(entity.content, back.content)
    }
}
