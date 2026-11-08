package com.example.persona.core.ai.mnn

import com.example.persona.core.ai.prompt.NativeMessage
import com.example.persona.core.memory.MemoryExtraction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3ProbeProtocolTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val revision = "sha256:" + "a".repeat(64)

    private fun document(): JsonObject {
        val rows = mutableListOf<Map<String, Any?>>()
        fun row(id: String, source: String?, messages: List<NativeMessage>, expected: List<Qwen3Expected>) {
            rows += linkedMapOf("id" to id, "kind" to if (source == null) "SMOKE" else "EXTRACTION",
                "source" to source, "messages" to messages, "referenceText" to "synthetic HF placeholder",
                "referenceIds" to listOf(1, 2, 3), "expected" to expected,
                "params" to mapOf("temperature" to 0.0, "topP" to 0.8, "maxTokens" to if (source == null) 24 else 256))
        }
        Qwen3ProbeProtocol.smoke.forEach { (id, messages) -> row(id, null, messages, emptyList()) }
        Qwen3ProbeProtocol.extraction.forEach { (id, fixture) ->
            row(id, fixture.source, listOf(NativeMessage("system", Qwen3ProbeProtocol.SYSTEM),
                NativeMessage("user", MemoryExtraction.prompt(fixture.source))), fixture.expected)
        }
        return gson.toJsonTree(mapOf("formatVersion" to 1, "kind" to "qwen3-native-probe-v1", "sourceRevision" to revision,
            "extractionVersion" to MemoryExtraction.VERSION, "datasetSha256" to Qwen3ProbeProtocol.DATASET_SHA256,
            "packageReceiptSha256" to Qwen3ProbeProtocol.RECEIPT_SHA256, "contextWindow" to 4096, "cases" to rows)).asJsonObject
    }

    private fun parse(text: String): Qwen3ProbeBundle {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Qwen3ProbeProtocol.parse(bytes, Qwen3ProbeJson.sha(bytes), revision)
    }

    private fun rejected(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected rejection", failed)
    }

    private fun changed(mutate: (JsonObject) -> Unit) {
        val root = document()
        mutate(root)
        rejected { parse(gson.toJson(root)) }
    }

    @Test fun acceptsAllNineWithoutClaimingReferenceValidity() {
        val bundle = parse(gson.toJson(document()))
        assertEquals(9, bundle.cases.size)
        assertEquals(6, bundle.cases.count { it.kind == "EXTRACTION" })
        assertEquals(Qwen3ProbeProtocol.RECEIPT_SHA256, bundle.packageReceiptSha256)
    }

    @Test fun fixturesMatchOriginalCorpusBytesAndModelExpected() {
        val file = File("tools/persona-memory-benchmark/extraction_samples.jsonl")
        assertEquals(Qwen3ProbeProtocol.DATASET_SHA256, Qwen3ProbeJson.sha(file.readBytes()))
        val samples = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            .map { Qwen3ProbeJson.parse(it.toByteArray(Charsets.UTF_8)).asJsonObject }.associateBy { it["id"].asString }
        Qwen3ProbeProtocol.extraction.forEach { (id, fixture) ->
            val original = samples.getValue(id)
            assertEquals(original["source"].asString, fixture.source)
            val expected = original["model"].asJsonArray.map { Qwen3Expected(it.asJsonObject["type"].asString, it.asJsonObject["content"].asString) }
            assertEquals(expected, fixture.expected)
        }
    }

    @Test fun rejectsUnknownAndMissingFieldsAtEachLevel() {
        changed { it.addProperty("extra", true) }
        changed { it.remove("sourceRevision") }
        changed { it["cases"].asJsonArray[0].asJsonObject.addProperty("extra", true) }
        changed { it["cases"].asJsonArray[0].asJsonObject.remove("source") }
        changed { it["cases"].asJsonArray[0].asJsonObject["params"].asJsonObject.addProperty("extra", 1) }
        changed { it["cases"].asJsonArray[0].asJsonObject["messages"].asJsonArray[0].asJsonObject.addProperty("extra", 1) }
        changed { it["cases"].asJsonArray[3].asJsonObject["expected"].asJsonArray[0].asJsonObject.addProperty("extra", 1) }
    }

    @Test fun rejectsDuplicateKeysIncludingEscapedAliasesAndNestedKeys() {
        rejected { parse(gson.toJson(document()).replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")) }
        rejected { Qwen3ProbeJson.parse("{\"a\":1,\"\\u0061\":2}".toByteArray()) }
        rejected { Qwen3ProbeJson.parse("{\"a\":{\"x\":1,\"x\":1}}".toByteArray()) }
    }

    @Test fun rejectsIntegerCoercionAndOverflow() {
        for (raw in listOf("1.0", "1e0", "-0", "-1", "2147483648", "\"1\"", "true", "01", "+1")) {
            rejected { parse(gson.toJson(document()).replace("\"formatVersion\":1", "\"formatVersion\":$raw")) }
            rejected { parse(gson.toJson(document()).replace("\"referenceIds\":[1,2,3]", "\"referenceIds\":[$raw]")) }
        }
    }

    @Test fun rejectsInvalidJsonStringsUtf8AndTrailingDocuments() {
        for (raw in listOf("{\"a\":\"\\'\"}", "{\"a\":\"a\nb\"}", "{\"a\":\"\\uXX12\"}", "{\"a\":1}{}", "\uFEFF{}")) {
            rejected { Qwen3ProbeJson.parse(raw.toByteArray()) }
        }
        rejected { Qwen3ProbeJson.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        rejected { parse(gson.toJson(document()).replace("synthetic HF placeholder", "\\ud800")) }
    }

    @Test fun rejectsBundleAndSourceAndReceiptReplacement() {
        val bytes = gson.toJson(document()).toByteArray()
        rejected { Qwen3ProbeProtocol.parse(bytes, "0".repeat(64), revision) }
        rejected { Qwen3ProbeProtocol.parse(bytes, Qwen3ProbeJson.sha(bytes), "sha256:" + "b".repeat(64)) }
        changed { it.addProperty("packageReceiptSha256", "b".repeat(64)) }
        changed { it.addProperty("datasetSha256", "b".repeat(64)) }
        changed { it.addProperty("extractionVersion", "v2") }
    }

    @Test fun rejectsMissingDuplicateOrUnknownCases() {
        changed { it["cases"].asJsonArray.remove(0) }
        changed { it["cases"].asJsonArray.add(it["cases"].asJsonArray[0].deepCopy()) }
        changed { it["cases"].asJsonArray[0].asJsonObject.addProperty("id", "other") }
        changed { it["cases"].asJsonArray[1].asJsonObject.addProperty("id", "smoke-single") }
    }

    @Test fun rejectsChangedSourceExpectedMessagesAndKinds() {
        changed { it["cases"].asJsonArray[3].asJsonObject.addProperty("source", "我喜欢茶") }
        changed { it["cases"].asJsonArray[3].asJsonObject["expected"].asJsonArray[0].asJsonObject.addProperty("content", "茶") }
        changed { it["cases"].asJsonArray[3].asJsonObject["messages"].asJsonArray[1].asJsonObject.addProperty("content", "copied prompt") }
        changed { it["cases"].asJsonArray[3].asJsonObject["messages"].asJsonArray[0].asJsonObject.addProperty("content", "other system") }
        changed { it["cases"].asJsonArray[0].asJsonObject.addProperty("kind", "EXTRACTION") }
        changed { it["cases"].asJsonArray[2].asJsonObject["messages"].asJsonArray[0].asJsonObject.addProperty("content", "/no_think") }
    }

    @Test fun enforcesReserveBoundaryAndExactParams() {
        val root = document()
        val extraction = root["cases"].asJsonArray[3].asJsonObject
        extraction.add("referenceIds", gson.toJsonTree(List(4096 - 256 - 56) { 1 }))
        assertEquals(3784, parse(gson.toJson(root)).cases[3].referenceIds.size)
        extraction["referenceIds"].asJsonArray.add(1)
        rejected { parse(gson.toJson(root)) }
        changed { it["cases"].asJsonArray[0].asJsonObject["params"].asJsonObject.addProperty("maxTokens", 256) }
        changed { it["cases"].asJsonArray[0].asJsonObject["params"].asJsonObject.addProperty("temperature", "0.0") }
        changed { it["cases"].asJsonArray[0].asJsonObject["params"].asJsonObject.addProperty("topP", 1.0) }
    }

    @Test fun rejectsUnsafeRunIdsAndPathsAndSymlinks() {
        val root = Files.createTempDirectory("qwen3-protocol-test").toFile().canonicalFile
        try {
            File(root, "qwen3-probe").mkdir()
            val run = File(root, "qwen3-probe/run-1").apply { mkdir() }
            val file = File(run, "valid.json").apply { writeText("{}") }
            assertEquals(run, Qwen3ProbePackage.existingRoot(root, "run-1"))
            for (id in listOf("../bad", "", ".", "a/b", "a b", "é", "x".repeat(65))) {
                rejected { Qwen3ProbePackage.existingRoot(root, id) }
            }
            for (path in listOf("../valid.json", "/valid.json", "a\\b", "./valid.json", "a//b")) {
                rejected { Qwen3ProbePackage.child(run, path) }
            }
            Files.createSymbolicLink(File(run, "alias.json").toPath(), file.toPath())
            rejected { Qwen3ProbePackage.child(run, "alias.json") }
            Files.createSymbolicLink(File(root, "qwen3-probe/alias").toPath(), run.toPath())
            rejected { Qwen3ProbePackage.existingRoot(root, "alias") }
            rejected { Qwen3ProbePackage.existingRoot(root, "not-provisioned") }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsSelfAuthoredReceiptEvenWhenCallerUsesItsHash() {
        val root = Files.createTempDirectory("qwen3-receipt-test").toFile().canonicalFile
        try {
            val bytes = "{}".toByteArray()
            File(root, "receipt.json").writeBytes(bytes)
            rejected { Qwen3ProbePackage.verify(root, Qwen3ProbeJson.sha(bytes)) }
            rejected { Qwen3ProbePackage.verify(root, Qwen3ProbeProtocol.RECEIPT_SHA256) }
        } finally { root.deleteRecursively() }
    }

    @Test fun strictProductionDecodeDoesNotCleanModelOutput() {
        val source = Qwen3ProbeProtocol.extraction.getValue("explicit-tea").source
        val valid = "{\"suggestions\":[{\"type\":\"PREFERENCE\",\"content\":\"我喜欢喝茶。\"}]}"
        assertEquals(1, MemoryExtraction.decode(valid, source).size)
        rejected { MemoryExtraction.decode("```json\n$valid\n```", source) }
        rejected { MemoryExtraction.decode("<think>reason</think>$valid", source) }
        assertTrue(MemoryExtraction.decode("{\"suggestions\":[]}", source).isEmpty())
        assertFalse(Qwen3ProbeProtocol.extraction.getValue("conflicting-preference").expected.isEmpty())
    }
}
