package com.example.persona.core.memory

import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import java.security.MessageDigest

object MemorySummary {
    const val VERSION = "memory-summary-extractive-v1"
    const val MAX_INPUT_MESSAGES = 128
    const val MAX_SOURCES = 8
    const val MAX_SOURCE_CHARS = 400
    const val MAX_TEXT_CHARS = 400

    data class Source(val messageId: String, val contentHash: String, val content: String)
    data class Result(val text: String, val sources: List<Source>, val sourceHash: String)

    // Input order is oldest to newest. Whole messages preserve trailing qualifications and negation.
    fun summarize(messages: List<Message>): Result {
        val recent = messages.takeLast(MAX_INPUT_MESSAGES)
        if (recent.map { it.personaId }.distinct().size > 1) return result(emptyList(), "")
        val seen = mutableSetOf<String>()
        val selected = mutableListOf<Source>()
        var length = 0
        for (message in recent.asReversed()) {
            // A later excluded or invalid duplicate must also suppress the older version.
            if (!seen.add(message.id)) continue
            if (message.id.isBlank() || !message.isFromUser || message.status != MessageStatus.NORMAL ||
                message.excludeFromHistory || message.content.isBlank() ||
                message.content.length > MAX_SOURCE_CHARS || !Charsets.UTF_8.newEncoder().canEncode(message.content)
            ) continue
            val size = message.content.length + if (selected.isEmpty()) 0 else 1
            if (length + size > MAX_TEXT_CHARS) break
            selected.add(Source(message.id, hash(message.content), message.content))
            length += size
            if (selected.size == MAX_SOURCES) break
        }
        return result(selected.asReversed().toList(), recent.firstOrNull()?.personaId.orEmpty())
    }

    fun build(messages: List<Message>): Result = summarize(messages)

    private fun result(sources: List<Source>, personaId: String): Result {
        // Length prefixes make source identity unambiguous even with embedded delimiters.
        val identity = buildString {
            appendPart(VERSION)
            if (sources.isNotEmpty()) appendPart(personaId)
            for (source in sources) {
                appendPart(source.messageId)
                appendPart(source.contentHash)
            }
        }
        return Result(sources.joinToString("\n") { it.content }, sources, hash(identity))
    }

    private fun StringBuilder.appendPart(value: String) {
        append(value.length).append(':').append(value)
    }

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
}
