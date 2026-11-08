package com.example.persona.core.ai.mnn

import com.example.persona.core.memory.MemoryExtraction
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class Qwen3ProbeQualityTest {
    private fun case(id: String): Qwen3ProbeCase {
        val fixture = Qwen3ProbeProtocol.extraction.getValue(id)
        return Qwen3ProbeCase(id, "EXTRACTION", fixture.source, emptyList(), "", emptyList(), 256, fixture.expected)
    }

    private fun raw(expected: List<Qwen3Expected>) = Gson().toJson(mapOf("suggestions" to expected))

    @Test fun exactSixCanMatchOnlyTheirFixedExpected() {
        val rows = Qwen3ProbeProtocol.extraction.keys.map { id ->
            val case = case(id)
            val result = Qwen3ProbeQuality.evaluate(case, raw(case.expected), false)
            assertEquals("matched", result["quality_status"])
            result + mapOf("id" to id, "engineering_status" to "passed")
        }
        assertEquals("passed_fixed_six", Qwen3ProbeQuality.fixedSixStatus(rows))
        assertEquals("not_evaluated_incomplete", Qwen3ProbeQuality.fixedSixStatus(rows.drop(1)))
        assertEquals("not_evaluated_incomplete", Qwen3ProbeQuality.fixedSixStatus(rows + rows.first()))
        assertEquals("not_evaluated_incomplete", Qwen3ProbeQuality.fixedSixStatus(rows.mapIndexed { i, row ->
            if (i == 0) row + ("engineering_status" to "failed") else row
        }))
    }

    @Test fun completedMismatchesDifferFromUnevaluatedRows() {
        val completed = Qwen3ProbeProtocol.extraction.keys.map { id ->
            mapOf<String, Any?>("id" to id, "engineering_status" to "passed", "quality_status" to "failed_fixed_case")
        }
        assertEquals("not_passed_fixed_six", Qwen3ProbeQuality.fixedSixStatus(completed))
        assertEquals("not_evaluated_incomplete", Qwen3ProbeQuality.fixedSixStatus(emptyList()))
        assertEquals("not_evaluated_incomplete", Qwen3ProbeQuality.fixedSixStatus(completed.mapIndexed { i, row ->
            if (i == 0) row + ("quality_status" to "not_evaluated") else row
        }))
    }

    @Test fun truncationFailsEvenWithStrictExactJson() {
        val case = case("explicit-tea")
        val result = Qwen3ProbeQuality.evaluate(case, raw(case.expected), true)
        assertEquals(true, result["exact_match"])
        assertEquals("failed_fixed_case", result["quality_status"])
    }

    @Test fun wrongTypeOrPartialContentIsNotExactMatch() {
        val case = case("explicit-tea")
        for (expected in listOf(listOf(Qwen3Expected("FACT", "我喜欢喝茶。")),
            listOf(Qwen3Expected("PREFERENCE", "喜欢喝茶")), emptyList())) {
            val result = Qwen3ProbeQuality.evaluate(case, raw(expected), false)
            assertEquals("passed", result["strict_decode"])
            assertEquals(false, result["exact_match"])
            assertEquals("failed_fixed_case", result["quality_status"])
        }
    }

    @Test fun negativeFalseExtractionIsSeparateFromParserFailure() {
        val case = case("fiction")
        val result = Qwen3ProbeQuality.evaluate(case, raw(listOf(Qwen3Expected("PREFERENCE", "我喜欢茶"))), false)
        assertEquals("passed", result["strict_decode"])
        assertEquals(true, result["semantic_negative_false_extraction"])
        assertEquals("failed_fixed_case", result["quality_status"])
        val malformed = Qwen3ProbeQuality.evaluate(case, "not-json", false)
        assertEquals("failed", malformed["strict_decode"])
        assertEquals(null, malformed["semantic_negative_false_extraction"])
    }

    @Test fun modelResponseIsNeverCleaned() {
        val case = case("explicit-tea")
        val valid = raw(case.expected)
        for (response in listOf("```json\n$valid\n```", "<think>thought</think>$valid", valid + " explanation",
            "{\"suggestions\":[],\"suggestions\":[]}", "x".repeat(MemoryExtraction.MAX_RAW_CHARS + 1))) {
            val result = Qwen3ProbeQuality.evaluate(case, response, false)
            assertEquals("failed", result["strict_decode"])
            assertEquals("failed_fixed_case", result["quality_status"])
        }
    }
}
