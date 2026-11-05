package com.example.persona.core.memory

import com.example.persona.domain.model.Memory
import java.text.Normalizer
import java.util.Locale

object MemoryRanker {
    const val VERSION = "memory-keyword-v2"
    private val words = Regex("[a-z0-9]+(?:[+#.][a-z0-9]+)*|[\\u3400-\\u9fff]+")
    private val questionWords = Regex(
        "你还记得|还记得|记不记得|不喜欢|不想要|不需要|我的|你的|我们|你们|" +
            "喜欢|什么|怎么|如何|是否|记得|记住|请问|帮我|告诉|一下|可以|不要|不吃|不喝"
    )
    private val aliases = listOf(
        setOf("咖啡", "coffee"), setOf("花生", "peanut", "peanuts"),
        setOf("猫咪", "小猫", "cat", "cats"), setOf("狗狗", "小狗", "dog", "dogs"),
        setOf("健身", "锻炼", "workout", "fitness"),
        setOf("茶", "喝茶", "茶饮", "tea"),
        setOf("人工智能", "ai"), setOf("javascript", "js"), setOf("python", "py")
    )

    fun rank(memories: List<Memory>, query: String): List<Memory> {
        if (query.isBlank()) return memories
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return memories.map { memory -> memory to terms(memory.content).count { it in queryTerms } }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }
                .thenByDescending { it.first.updatedAt }.thenBy { it.first.id })
            .map { it.first }
    }

    // Negation is ignored only for topic matching. The original evidence is returned intact.
    internal fun terms(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = mutableSetOf<String>()
        for (match in words.findAll(questionWords.replace(normalized, " "))) {
            val word = match.value
            if (word.first() in '\u3400'..'\u9fff') {
                result.addAll(word.windowed(2))
            } else {
                result.add(word)
            }
        }
        for ((index, group) in aliases.withIndex()) {
            if (group.any { alias ->
                    if (alias.first() in '\u3400'..'\u9fff') normalized.contains(alias) else alias in result
                }) {
                result.removeAll(group)
                result.add("alias-$index")
            }
        }
        return result
    }
}
