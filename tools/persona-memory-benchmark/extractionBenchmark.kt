package com.example.persona.benchmark

import com.example.persona.core.memory.MemoryExtraction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected fixed samples path and output report path" }
    val fixture = File(args[0])
    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    val samples = fixture.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        .map { JsonParser().parse(it).asJsonObject }
    val ids = samples.map { it.get("id").asString }
    require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Invalid sample identities" }
    val rows = samples.map { sample ->
        val source = sample.get("source").asString
        val expectedRule = expected(sample, "explicit")
        val expectedModel = expected(sample, "model")
        val started = System.nanoTime()
        val rule = MemoryExtraction.explicit(source)
        val ruleNanos = System.nanoTime() - started
        val selectedStarted = System.nanoTime()
        val selected = MemoryExtraction.selected(source)
        val selectedNanos = System.nanoTime() - selectedStarted
        val selectedType = sample.get("selectedType").takeUnless { it.isJsonNull }?.asString
        val selectedMatches = if (selectedType == null) selected.isEmpty() else
            selected.size == 1 && selected.single().type.name == selectedType && selected.single().content == source
        val decodedReference = MemoryExtraction.decode(gson.toJson(mapOf("suggestions" to expectedModel)), source)
        mapOf(
            "id" to sample.get("id").asString,
            "rule_contract_matches" to (values(rule) == expectedRule),
            "selected_contract_matches" to selectedMatches,
            "schema_reference_matches" to (values(decodedReference) == expectedModel),
            "rule_candidates" to rule.size,
            "selected_candidates" to selected.size,
            "rule_elapsed_ns" to ruleNanos,
            "selected_elapsed_ns" to selectedNanos,
            "device_mnn" to "unrun"
        )
    }
    val failures = rows.filter { row ->
        row["rule_contract_matches"] != true || row["selected_contract_matches"] != true ||
            row["schema_reference_matches"] != true
    }.map { it["id"] }
    val report = mapOf(
        "schema" to "persona-memory-extraction-contract-v1",
        "synthetic" to true,
        "extraction_version" to MemoryExtraction.VERSION,
        "dataset_sha256" to MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) },
        "cases" to rows.size,
        "contract_pass" to (rows.size - failures.size),
        "failures" to failures,
        "device_mnn" to mapOf("status" to "unrun", "model" to null, "observations" to emptyList<Any>()),
        "rows" to rows
    )
    File(args[1]).writeText(gson.toJson(report) + "\n", Charsets.UTF_8)
    println("Synthetic extraction contracts ${rows.size - failures.size}/${rows.size}")
    println("Device MNN unrun")
    check(failures.isEmpty()) { "Synthetic extraction contract failures: $failures" }
}

private fun expected(sample: JsonObject, name: String): List<Map<String, String>> =
    sample.getAsJsonArray(name).map { item ->
        val value = item.asJsonObject
        mapOf("type" to value.get("type").asString, "content" to value.get("content").asString)
    }

private fun values(items: List<MemoryExtraction.Suggestion>): List<Map<String, String>> =
    items.map { mapOf("type" to it.type.name, "content" to it.content) }
