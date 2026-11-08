package com.example.persona.core.ai.mnn

import com.example.persona.core.memory.MemoryExtraction

internal object Qwen3ProbeQuality {
    fun evaluate(case: Qwen3ProbeCase, raw: String, truncated: Boolean): Map<String, Any?> {
        val source = requireNotNull(case.source)
        return try {
            val actual = MemoryExtraction.decode(raw, source).map { Qwen3Expected(it.type.name, it.content) }
            mapOf("strict_decode" to "passed", "decoded" to actual,
                "exact_match" to (actual == case.expected),
                "semantic_negative_false_extraction" to (case.expected.isEmpty() && actual.isNotEmpty()),
                "quality_status" to if (actual == case.expected && !truncated) "matched" else "failed_fixed_case")
        } catch (error: Exception) {
            mapOf("strict_decode" to "failed", "decode_error_type" to error.javaClass.simpleName,
                "semantic_negative_false_extraction" to null, "quality_status" to "failed_fixed_case")
        }
    }

    fun fixedSixStatus(rows: List<Map<String, Any?>>): String {
        val extraction = rows.filter { it["id"] in Qwen3ProbeProtocol.extraction.keys }
        if (extraction.size != 6 || extraction.map { it["id"] }.toSet() != Qwen3ProbeProtocol.extraction.keys ||
            extraction.any { it["engineering_status"] != "passed" || it["quality_status"] !in setOf("matched", "failed_fixed_case") })
            return "not_evaluated_incomplete"
        return if (extraction.all { it["quality_status"] == "matched" })
            "passed_fixed_six" else "not_passed_fixed_six"
    }
}
