package com.example.persona.evaluation

import com.example.persona.core.ai.LocalModelManifest
import com.example.persona.core.ai.LocalModelManifestFile
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

internal object M5LifecycleModelFixture {
    // Validator-only dummy files for M5ControlledLocalEngine; never load them with MNN/JNI.
    fun write(root: File, id: String): File {
        check(root.isDirectory || root.mkdirs()) { "Cannot create synthetic model directory" }
        val entry = "llm.mnn"
        val weight = "llm.mnn.weight"
        val tokenizer = "tokenizer.txt"
        root.resolve("config.json").writeText("""{"llm_model":"$entry","llm_weight":"$weight"}""")
        root.resolve(entry).writeText("synthetic-model-not-for-mnn")
        root.resolve(weight).writeText("synthetic-weight-not-for-mnn")
        root.resolve(tokenizer).writeText("430 3\n!\n")
        val files = listOf(
            "config.json" to "config",
            entry to "model",
            weight to "weight",
            tokenizer to "tokenizer"
        ).map { (path, role) ->
            val file = root.resolve(path)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            LocalModelManifestFile(
                path = path,
                role = role,
                size = file.length(),
                sha256 = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            )
        }
        val manifest = LocalModelManifest(
            schemaVersion = 1,
            id = id,
            name = "Synthetic lifecycle fixture",
            version = "1.0.0",
            backend = "MNN",
            family = ModelFamilies.QWEN2_5,
            promptFormat = PromptFormats.QWEN_CHATML_TEXT,
            entry = entry,
            tokenizer = tokenizer,
            contextWindow = 2048,
            minRamGb = 0,
            minSdk = 24,
            requiredAbis = listOf("arm64-v8a"),
            files = files
        )
        root.resolve("manifest.json").writeText(Gson().toJson(manifest))
        root.resolve("READY").writeText("ready\n")
        return root
    }
}
