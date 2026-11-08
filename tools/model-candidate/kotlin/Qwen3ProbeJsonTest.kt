package com.example.persona.core.ai.mnn

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Qwen3ProbeJsonTest {
    @Test fun rejectsNonJsonNullAndBooleanLexemes() {
        for (literal in listOf("NULL", "Null", "nUll", "nulL", "TRUE", "True", "truE", "FALSE", "False", "fAlse", "falsE")) {
            var rejected = false
            try { Qwen3ProbeJson.parse("{\"value\":$literal}".toByteArray()) }
            catch (_: Exception) { rejected = true }
            assertTrue("Non-JSON literal accepted", rejected)
        }
    }

    @Test fun acceptsOnlyLowercaseLiteralsWithoutInspectingQuotedContent() {
        val result = Qwen3ProbeJson.parse("[null,true,false,1E+2,\"NULL False truE\"]".toByteArray()).asJsonArray
        assertTrue(result[0].isJsonNull)
        assertEquals(true, result[1].asBoolean)
        assertEquals(false, result[2].asBoolean)
        assertEquals("NULL False truE", result[4].asString)
    }

    private fun safeRejected(block: () -> Unit) {
        var error: Exception? = null
        try { block() } catch (caught: Exception) { error = caught }
        assertTrue("Expected private input failure", error is IllegalArgumentException)
        assertEquals("Qwen3ProbeInputFailure", error?.message)
        assertNull(error?.cause)
    }

    @Test fun boundedReadAcceptsExactLimitAndRejectsExtraByteForEveryJsonRole() {
        val root = Files.createTempDirectory("qwen3-bounded-test").toFile().canonicalFile
        try {
            val data = ByteArray(Qwen3ProbeJson.MAX_BYTES) { ' '.code.toByte() }
            data[0] = '{'.code.toByte(); data[1] = '}'.code.toByte()
            for (name in listOf("bundle.json", "receipt.json", "config.json", "context.json", "llm_config.json")) {
                val file = File(root, name)
                file.writeBytes(data)
                assertEquals(Qwen3ProbeJson.MAX_BYTES, Qwen3ProbeJson.readFile(root, name).size)
                assertTrue(Qwen3ProbeJson.parse(Qwen3ProbeJson.readFile(root, name)).isJsonObject)
                file.appendBytes(byteArrayOf(' '.code.toByte()))
                safeRejected { Qwen3ProbeJson.readFile(root, name) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedReadRejectsAliasesMissingFilesAndCorruptionWithPrivateErrors() {
        val root = Files.createTempDirectory("qwen3-safe-read-test").toFile().canonicalFile
        try {
            val file = File(root, "input.json").apply { writeText("{}") }
            Files.createSymbolicLink(File(root, "alias.json").toPath(), file.toPath())
            for (path in listOf("alias.json", "./input.json", "../input.json", "missing.json")) {
                safeRejected { Qwen3ProbeJson.readFile(root, path) }
            }
            for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), "{broken".toByteArray(), byteArrayOf())) {
                file.writeBytes(bytes)
                safeRejected { Qwen3ProbeJson.parse(Qwen3ProbeJson.readFile(root, "input.json")) }
            }
        } finally { root.deleteRecursively() }
    }
}
