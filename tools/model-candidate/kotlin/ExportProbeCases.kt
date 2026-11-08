package com.example.persona.candidate

import com.example.persona.core.ai.ChatMessage
import com.example.persona.core.ai.prompt.ContextPlanner
import com.example.persona.core.memory.MemoryExtraction
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 2)
    val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    val output = File(args[1])
    require(!output.exists())
    val selected = listOf("explicit-tea", "explicit-negation", "explicit-goal", "recall-question", "fiction", "conflicting-preference")
    val samples = File(args[0]).readLines(Charsets.UTF_8).filter(String::isNotBlank)
        .map { JsonParser().parse(it).asJsonObject }.associateBy { it.get("id").asString }
    val rows = mutableListOf<Map<String, Any?>>()
    fun add(id: String, kind: String, source: String?, messages: List<ChatMessage>, expected: Any) {
        rows += mapOf("id" to id, "kind" to kind, "source" to source,
            "messages" to messages, "legacyPlanningText" to ContextPlanner.renderForPlanning(messages),
            "params" to mapOf("temperature" to 0.0, "topP" to 0.8,
                "maxTokens" to if (kind == "SMOKE") 24 else 256), "expected" to expected)
    }
    add("smoke-single", "SMOKE", null, listOf(ChatMessage("user", "用一个词回答：天空是什么颜色？")), emptyList<Any>())
    add("smoke-multi", "SMOKE", null, listOf(ChatMessage("user", "这是合成测试。"),
        ChatMessage("assistant", "收到。"), ChatMessage("user", "请回答：你好🙂\n测试结束。")), emptyList<Any>())
    add("smoke-think-input", "SMOKE", null, listOf(ChatMessage("user", "/think 用一个词回答：一加一等于几？")), emptyList<Any>())
    selected.forEach { id ->
        val sample = requireNotNull(samples[id])
        val source = sample.get("source").asString
        val expected = sample.getAsJsonArray("model")
        MemoryExtraction.decode(gson.toJson(mapOf("suggestions" to expected)), source)
        add(id, "EXTRACTION", source, listOf(ChatMessage("system", "只从用户原文提取可确认资料，输出 JSON。"),
            ChatMessage("user", MemoryExtraction.prompt(source))), expected)
    }
    output.writeText(gson.toJson(mapOf("extractionVersion" to MemoryExtraction.VERSION, "cases" to rows)) + "\n", Charsets.UTF_8)
    println("Production Kotlin exported nine synthetic probe cases")
}
