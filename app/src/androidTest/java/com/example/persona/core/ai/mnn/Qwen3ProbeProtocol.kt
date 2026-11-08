package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.prompt.NativeMessage
import com.example.persona.core.memory.MemoryExtraction

internal data class Qwen3Expected(val type: String, val content: String)
internal data class Qwen3Fixture(val source: String, val expected: List<Qwen3Expected>)
internal data class Qwen3ProbeCase(
    val id: String, val kind: String, val source: String?, val messages: List<NativeMessage>,
    val referenceText: String, val referenceIds: List<Int>, val maxTokens: Int,
    val expected: List<Qwen3Expected>
)
internal data class Qwen3ProbeBundle(
    val sourceRevision: String, val packageReceiptSha256: String, val cases: List<Qwen3ProbeCase>
)

internal object Qwen3ProbeProtocol {
    const val DATASET_SHA256 = "9345975b9f5763e718cc2ae063c958ea2a10decdb2697e33ea3cbd577c7abcdd"
    const val RECEIPT_SHA256 = "1775292d3ecaa87a0d776cc4e773a8452294b9b3a4fdd4e3b1f82ff413e39b32"
    const val SYSTEM = "只从用户原文提取可确认资料，输出 JSON。"
    val hex = Regex("[0-9a-f]{64}")
    val revision = Regex("sha256:[0-9a-f]{64}")
    val runId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    val smoke = linkedMapOf(
        "smoke-single" to listOf(NativeMessage("user", "用一个词回答：天空是什么颜色？")),
        "smoke-multi" to listOf(NativeMessage("user", "这是合成测试。"), NativeMessage("assistant", "收到。"),
            NativeMessage("user", "请回答：你好🙂\n测试结束。")),
        "smoke-think-input" to listOf(NativeMessage("user", "/think 用一个词回答：一加一等于几？"))
    )
    val extraction = linkedMapOf(
        "explicit-tea" to Qwen3Fixture("记住，我喜欢喝茶。", listOf(Qwen3Expected("PREFERENCE", "我喜欢喝茶。"))),
        "explicit-negation" to Qwen3Fixture("请记住：我不喜欢咖啡。", listOf(Qwen3Expected("PREFERENCE", "我不喜欢咖啡。"))),
        "explicit-goal" to Qwen3Fixture("记住，我的目标是明年跑完马拉松", listOf(Qwen3Expected("GOAL", "我的目标是明年跑完马拉松"))),
        "recall-question" to Qwen3Fixture("你记住我喜欢什么吗？", emptyList()),
        "fiction" to Qwen3Fixture("记住，假设我喜欢茶", emptyList()),
        "conflicting-preference" to Qwen3Fixture("记住我不喜欢茶", listOf(Qwen3Expected("PREFERENCE", "我不喜欢茶")))
    )

    fun parse(bytes: ByteArray, bundleSha256: String, sourceRevision: String): Qwen3ProbeBundle {
        require(hex.matches(bundleSha256) && revision.matches(sourceRevision))
        require(Qwen3ProbeJson.sha(bytes) == bundleSha256)
        val root = Qwen3ProbeJson.parse(bytes).fields("formatVersion", "kind", "sourceRevision", "extractionVersion",
            "datasetSha256", "packageReceiptSha256", "contextWindow", "cases")
        require(root["formatVersion"].integer() == 1 && root["contextWindow"].integer() == 4096)
        require(root["kind"].string() == "qwen3-native-probe-v1")
        require(root["sourceRevision"].string() == sourceRevision)
        require(root["extractionVersion"].string() == MemoryExtraction.VERSION)
        require(root["datasetSha256"].string() == DATASET_SHA256)
        val receipt = root["packageReceiptSha256"].string()
        require(receipt == RECEIPT_SHA256)
        val cases = root["cases"].array().map { element ->
            val row = element.fields("id", "kind", "source", "messages", "referenceText", "referenceIds", "params", "expected")
            val id = row["id"].string()
            val source = row["source"].let { if (it.isJsonNull) null else it.string() }
            val messages = row["messages"].array().map {
                val message = it.fields("role", "content")
                NativeMessage(message["role"].string(), message["content"].string())
            }
            val expected = row["expected"].array().map {
                val item = it.fields("type", "content")
                Qwen3Expected(item["type"].string(), item["content"].string())
            }
            val kind = row["kind"].string()
            val maxTokens = if (id in smoke) {
                require(kind == "SMOKE" && source == null && expected.isEmpty() && messages == smoke[id])
                24
            } else {
                val fixture = requireNotNull(extraction[id])
                require(kind == "EXTRACTION" && source == fixture.source && expected == fixture.expected)
                require(messages == listOf(NativeMessage("system", SYSTEM), NativeMessage("user", MemoryExtraction.prompt(fixture.source))))
                256
            }
            val params = row["params"].fields("temperature", "topP", "maxTokens")
            require(params["temperature"].isJsonPrimitive && params["temperature"].asJsonPrimitive.isNumber)
            require(params["temperature"].asString == "0.0")
            require(params["topP"].isJsonPrimitive && params["topP"].asJsonPrimitive.isNumber && params["topP"].asString == "0.8")
            require(params["maxTokens"].integer() == maxTokens)
            val ids = row["referenceIds"].array().map { it.integer() }
            require(ids.isNotEmpty() && ids.size + maxTokens + 56 <= 4096)
            val text = row["referenceText"].string()
            require(text.isNotEmpty())
            Qwen3ProbeCase(id, kind, source, messages, text, ids, maxTokens, expected)
        }
        require(cases.size == 9 && cases.map { it.id }.toSet() == smoke.keys + extraction.keys)
        return Qwen3ProbeBundle(sourceRevision, receipt, cases)
    }
}
