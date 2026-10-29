package com.example.persona.core.ai

import com.example.persona.testutil.ModelPackageFixtures
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LocalModelContractTest(private val case: JsonObject) {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sharedPackageContract() {
        val root = temporaryFolder.newFolder()
        val payloads = Gson().fromJson(contract.getAsJsonObject("payloads").toString(), JsonObject::class.java)
        case.getAsJsonObject("payloads")?.entrySet()?.forEach { payloads.add(it.key, it.value) }
        case.getAsJsonObject("config")?.let { payloads.addProperty("config.json", it.toString()) }
        payloads.entrySet().forEach { (path, contents) -> root.resolve(path).writeText(contents.asString) }
        val manifest = Gson().fromJson(contract.getAsJsonObject("manifest").toString(), JsonObject::class.java)
        val omitted = case.getAsJsonArray("omitEntries")?.map { it.asString }.orEmpty()
        val entries = JsonArray()
        payloads.keySet().filterNot(omitted::contains).forEach { path ->
            val file = root.resolve(path)
            val spec = JsonObject().apply {
                addProperty("path", path)
                addProperty("role", "file")
                addProperty("size", file.length())
                addProperty("sha256", ModelPackageFixtures.sha256(file))
            }
            case.getAsJsonObject("fileOverrides")?.getAsJsonObject(path)?.entrySet()
                ?.forEach { spec.add(it.key, it.value) }
            entries.add(spec)
        }
        manifest.add("files", entries)
        case.getAsJsonObject("manifest")?.entrySet()?.forEach { manifest.add(it.key, it.value) }
        root.resolve("manifest.json").writeText(manifest.toString())
        root.resolve("READY").writeText("ready\n")
        case.getAsJsonArray("removeFiles")?.forEach { root.resolve(it.asString).delete() }

        val result = LocalModelValidator(SymlinkChecker { false })
            .validate(root, ModelPackageFixtures.deviceProfile()).result

        assertEquals(case.toString(), case.get("accept").asBoolean, result is ModelScanResult.Ready)
        if (result is ModelScanResult.Ready) {
            assertTrue(result.admission.reasons.contains(LocalModelValidator.UNVERIFIED_SOURCE_WARNING))
        }
    }

    companion object {
        private val contract = LocalModelContractTest::class.java
            .getResourceAsStream("/model-package-contract.json")!!.bufferedReader().use {
                Gson().fromJson(it, JsonObject::class.java)
            }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<JsonObject>> = contract.getAsJsonArray("cases").map { arrayOf(it.asJsonObject) }
    }
}
