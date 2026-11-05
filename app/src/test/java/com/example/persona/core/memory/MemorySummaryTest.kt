package com.example.persona.core.memory

import com.example.persona.domain.model.Message
import com.example.persona.domain.model.MessageStatus
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySummaryTest {
    @Test
    fun `empty and ineligible inputs produce no text or references`() {
        val excluded = listOf(
            message("assistant", "Assistant inference").copy(isFromUser = false),
            message("generating", "In progress").copy(status = MessageStatus.GENERATING),
            message("failed", "Partial").copy(status = MessageStatus.FAILED),
            message("stopped", "Partial").copy(status = MessageStatus.STOPPED),
            message("excluded", "Deleted memory source").copy(excludeFromHistory = true),
            message("blank", " \n\t"), message("", "Untraceable")
        )
        assertEquals(MemorySummary.summarize(emptyList()), MemorySummary.summarize(excluded))
        assertEquals("", MemorySummary.summarize(excluded).text)
        assertTrue(MemorySummary.summarize(excluded).sources.isEmpty())
    }

    @Test
    fun `summary quotes complete user content with exact references and content hashes`() {
        val messages = listOf(message("first", "我以前喜欢咖啡。"), message("second", "现在我不喝咖啡，但周末例外。"))
        val result = MemorySummary.summarize(messages)
        assertEquals(messages.joinToString("\n") { it.content }, result.text)
        assertEquals(messages.map { it.id }, result.sources.map { it.messageId })
        assertEquals(messages.map { it.content }, result.sources.map { it.content })
        assertEquals(messages.map { sha256(it.content) }, result.sources.map { it.contentHash })
        assertTrue(result.sourceHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(result, MemorySummary.summarize(messages))
    }

    @Test
    fun `source hash changes for edits deletion exclusion identities scope and ordering`() {
        val first = message("first", "我喜欢茶")
        val second = message("second", "我不喝咖啡")
        val original = MemorySummary.summarize(listOf(first, second))
        val changed = listOf(
            listOf(first.copy(content = "我不喜欢茶"), second), listOf(first),
            listOf(first.copy(excludeFromHistory = true), second),
            listOf(first.copy(isFromUser = false), second),
            listOf(first.copy(status = MessageStatus.FAILED), second),
            listOf(first.copy(id = "renamed"), second),
            listOf(first.copy(personaId = "other"), second.copy(personaId = "other")),
            listOf(second, first)
        )
        for (messages in changed) assertNotEquals(messages.toString(), original.sourceHash, MemorySummary.summarize(messages).sourceHash)
    }

    @Test
    fun `recent input order is preserved and source count is bounded`() {
        val messages = (0..20).map { message("id-$it", "原文$it") }
        val result = MemorySummary.summarize(messages)
        assertEquals(messages.takeLast(MemorySummary.MAX_SOURCES).map { it.id }, result.sources.map { it.messageId })
        assertEquals(result.sources.joinToString("\n") { it.content }, result.text)
    }

    @Test
    fun `summary budget includes separators and never clips a message`() {
        val messages = (0..9).map { message("id-$it", "茶".repeat(399) + it) }
        val result = MemorySummary.summarize(messages)
        assertTrue(result.text.length <= MemorySummary.MAX_TEXT_CHARS)
        assertEquals(400, MemorySummary.MAX_TEXT_CHARS)
        assertEquals(listOf("id-9"), result.sources.map { it.messageId })
        assertTrue(result.sources.all { source -> messages.any { it.id == source.messageId && it.content == source.content } })
    }

    @Test
    fun `long messages are skipped entirely to preserve trailing qualifications`() {
        val long = message("long", "茶".repeat(MemorySummary.MAX_SOURCE_CHARS) + "，以上全是假设")
        val exact = message("exact", "茶".repeat(MemorySummary.MAX_SOURCE_CHARS))
        val result = MemorySummary.summarize(listOf(long, exact))
        assertEquals(listOf("exact"), result.sources.map { it.messageId })
        assertEquals(exact.content, result.text)
    }

    @Test
    fun `bounded scan never revives an old source beyond recent input window`() {
        val old = message("old", "Old eligible fact")
        val recent = List(MemorySummary.MAX_INPUT_MESSAGES) {
            message("assistant-$it", "Assistant").copy(isFromUser = false)
        }
        assertTrue(MemorySummary.summarize(listOf(old) + recent).sources.isEmpty())
    }

    @Test
    fun `latest duplicate identity wins including disqualification`() {
        val original = message("same", "Old content")
        val revised = original.copy(content = "Current content")
        assertEquals(listOf("Current content"), MemorySummary.summarize(listOf(original, revised)).sources.map { it.content })
        assertTrue(MemorySummary.summarize(listOf(original, revised.copy(excludeFromHistory = true))).sources.isEmpty())
        assertTrue(MemorySummary.summarize(listOf(original, revised.copy(status = MessageStatus.FAILED))).sources.isEmpty())
    }

    @Test
    fun `mixed persona inputs fail closed instead of combining sources`() {
        val mixed = listOf(message("one", "One"), message("two", "Two").copy(personaId = "other"))
        assertEquals("", MemorySummary.summarize(mixed).text)
    }

    @Test
    fun `summary preserves whitespace negation Unicode and local only eligibility`() {
        val original = message("source", "  我不喜欢😀\n只有周末例外。  ").copy(localOnly = true)
        val result = MemorySummary.summarize(listOf(original))
        assertEquals(original.content, result.text)
        assertEquals(sha256(original.content), result.sources.single().contentHash)
        assertFalse(result.text.contains("用户喜欢"))
        assertEquals(result, MemorySummary.build(listOf(original)))
    }

    @Test
    fun `separator consumes the strict 400 char budget`() {
        val first = message("first", "a".repeat(200))
        val second = message("second", "b".repeat(200))
        assertEquals(listOf("second"), MemorySummary.summarize(listOf(first, second)).sources.map { it.messageId })
        val exact = first.copy(content = "a".repeat(199))
        val result = MemorySummary.summarize(listOf(exact, second))
        assertEquals(400, result.text.length)
        assertEquals(listOf("first", "second"), result.sources.map { it.messageId })
    }

    @Test
    fun `ill formed Unicode is excluded before UTF8 hashing can lose source distinctions`() {
        assertTrue(MemorySummary.summarize(listOf(message("bad", "我喜欢\uD800"))).sources.isEmpty())
        val exact = message("emoji", "😀".repeat(200))
        assertEquals(exact.content, MemorySummary.summarize(listOf(exact)).text)
        assertTrue(MemorySummary.summarize(listOf(exact.copy(content = exact.content + "a"))).sources.isEmpty())
    }

    private fun message(id: String, content: String) = Message(
        id = id, personaId = "persona", content = content, isFromUser = true, timestamp = 1L
    )

    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
