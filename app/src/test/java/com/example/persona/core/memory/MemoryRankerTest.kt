package com.example.persona.core.memory

import com.example.persona.domain.model.Memory
import com.example.persona.domain.model.MemoryType
import org.junit.Assert.*
import org.junit.Test

class MemoryRankerTest {
    @Test
    fun `two character Chinese terms recall old facts without single character noise`() {
        val rows = listOf(note("new", "我喜欢音色和乐器", 10), note("old", "我喜欢音乐", 1))
        assertEquals(listOf("old"), MemoryRanker.rank(rows, "音乐").map { it.id })
    }

    @Test
    fun `aliases and mixed case English match whole Latin tokens`() {
        val rows = listOf(note("noise", "I use javascript"), note("coffee", "我不喝咖啡"),
            note("code", "我用 Kotlin 写 Android"), note("js", "我用 JS"))
        assertEquals(listOf("coffee"), MemoryRanker.rank(rows, "coffee").map { it.id })
        assertEquals(listOf("code"), MemoryRanker.rank(rows, "kOTLin 开发").map { it.id })
        assertTrue(MemoryRanker.rank(rows, "java").isEmpty())
        assertEquals(listOf("js", "noise"), MemoryRanker.rank(rows, "JavaScript").map { it.id })
    }

    @Test
    fun `negation remains evidence even for a positive question`() {
        val denied = note("denied", "我不喜欢咖啡，也不吃花生")
        assertEquals(listOf(denied), MemoryRanker.rank(listOf(denied), "喜欢咖啡吗"))
        assertEquals(listOf(denied), MemoryRanker.rank(listOf(denied), "不吃什么花生食品"))
        assertEquals("我不喜欢咖啡，也不吃花生", denied.content)
    }

    @Test
    fun `generic question words and punctuation do not recall unrelated facts`() {
        val rows = listOf(note("noise", "我喜欢旅行，你喜欢什么"))
        for (query in listOf("天文", "我喜欢什么", "你还记得吗", "？！")) {
            assertTrue(query, MemoryRanker.rank(rows, query).isEmpty())
        }
    }

    @Test
    fun `ties use recency then ID independent of input order`() {
        val rows = listOf(note("b", "咖啡", 2), note("a", "咖啡", 2), note("old", "咖啡", 1))
        assertEquals(listOf("a", "b", "old"), MemoryRanker.rank(rows.reversed(), "咖啡").map { it.id })
        assertEquals(MemoryRanker.rank(rows, "咖啡"), MemoryRanker.rank(rows.shuffled(), "咖啡"))
    }

    @Test
    fun `blank query preserves the supplied legacy ordering`() {
        val rows = listOf(note("b", "茶", 1), note("a", "咖啡", 2))
        assertEquals(rows, MemoryRanker.rank(rows, " \n "))
    }

    @Test
    fun `more matching topics outrank recency and repeated words do not inflate scores`() {
        val rows = listOf(note("new", "花生放在抽屉", 10), note("old", "我对花生过敏", 1))
        assertEquals(listOf("old", "new"), MemoryRanker.rank(rows, "花生过敏").map { it.id })
        assertEquals(MemoryRanker.rank(rows, "花生过敏"), MemoryRanker.rank(rows, "花生过敏".repeat(10)))
    }

    @Test
    fun `existing personal tea question recalls the saved tea preference`() {
        val tea = note("tea", "用户明确保存：最喜欢喝桂花茶。", 1).copy(type = MemoryType.PREFERENCE)
        assertEquals(listOf(tea), MemoryRanker.rank(listOf(tea),
            "根据你当前可用的信息，我最喜欢喝什么茶？如果不知道就直接说不知道。一句话回答。"))
    }

    private fun note(id: String, content: String, time: Long = 1) = Memory(
        id, "owner", "p", MemoryType.FACT, content, createdAt = time, updatedAt = time
    )
}
