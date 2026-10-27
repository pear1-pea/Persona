package com.example.persona.testutil

import com.example.persona.core.ai.DeviceProfile
import com.example.persona.core.ai.LocalModelManifest
import com.example.persona.core.ai.LocalModelManifestFile
import com.example.persona.core.ai.ModelFamilies
import com.example.persona.core.ai.PromptFormats
import com.example.persona.core.ai.ThermalStatus
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

object ModelPackageFixtures {

    const val DEFAULT_ID = "qwen2.5-0.5b-instruct-mnn"
    const val DEFAULT_ENTRY = "llm.mnn"
    const val DEFAULT_WEIGHT = "llm.mnn.weight"
    const val DEFAULT_TOKENIZER = "tokenizer.txt"

    fun deviceProfile(
        abi: String = "arm64-v8a",
        sdk: Int = 36,
        totalRamBytes: Long = 8_000_000_000L,
        availableMemBytes: Long = 4_000_000_000L,
        appMemoryClassBytes: Long = 512_000_000L,
        nativePssBytes: Long = 100_000_000L,
        freeStorageBytes: Long = 4_000_000_000L,
        thermalStatus: ThermalStatus = ThermalStatus.NOMINAL,
        batteryPercent: Int = 80,
        powerSaveMode: Boolean = false
    ): DeviceProfile = DeviceProfile(
        totalRamBytes = totalRamBytes,
        availableMemBytes = availableMemBytes,
        appMemoryClassBytes = appMemoryClassBytes,
        nativePssBytes = nativePssBytes,
        freeStorageBytes = freeStorageBytes,
        abi = abi,
        sdk = sdk,
        thermalStatus = thermalStatus,
        batteryPercent = batteryPercent,
        powerSaveMode = powerSaveMode
    )

    /**
     * Writes a minimal but valid MNN model package. Tests can mutate the returned
     * directory or use the hooks to produce invalid manifests.
     */
    fun writePackage(
        root: File,
        id: String = DEFAULT_ID,
        entry: String = DEFAULT_ENTRY,
        configEntry: String = entry,
        weight: String = DEFAULT_WEIGHT,
        tokenizer: String = DEFAULT_TOKENIZER,
        ready: Boolean = true,
        contextWindow: Int = 32_768,
        minRamGb: Int = 4,
        minSdk: Int = 24,
        requiredAbis: List<String> = listOf("arm64-v8a"),
        family: String = ModelFamilies.QWEN2_5,
        promptFormat: String = PromptFormats.QWEN_CHATML_TEXT,
        backend: String = "MNN",
        schemaVersion: Int = 1,
        files: ((File) -> MutableList<LocalModelManifestFile>)? = null,
        manifestTransform: (LocalModelManifest) -> LocalModelManifest = { it }
    ): File {
        root.mkdirs()
        root.resolve("config.json").writeText(
            """{"llm_model":"$configEntry","llm_weight":"$weight"}"""
        )
        root.resolve(entry).writeText("entry-file")
        root.resolve(weight).writeText("weight-file")
        root.resolve(tokenizer).writeText("tokenizer-file")

        val manifestFiles = files?.invoke(root)
            ?: defaultFiles(root, entry, weight, tokenizer)
        val manifest = manifestTransform(
            LocalModelManifest(
                schemaVersion = schemaVersion,
                id = id,
                name = "Qwen2.5 0.5B Instruct",
                version = "1.0.0",
                backend = backend,
                family = family,
                promptFormat = promptFormat,
                entry = entry,
                tokenizer = tokenizer,
                contextWindow = contextWindow,
                minRamGb = minRamGb,
                minSdk = minSdk,
                requiredAbis = requiredAbis,
                files = manifestFiles
            )
        )
        root.resolve("manifest.json").writeText(Gson().toJson(manifest))
        if (ready) {
            root.resolve("READY").writeText("ready\n")
        }
        return root
    }

    fun defaultFiles(
        root: File,
        entry: String = DEFAULT_ENTRY,
        weight: String = DEFAULT_WEIGHT,
        tokenizer: String = DEFAULT_TOKENIZER
    ): MutableList<LocalModelManifestFile> = mutableListOf(
        fileSpec(root, "config.json", "config"),
        fileSpec(root, entry, "model"),
        fileSpec(root, weight, "weight"),
        fileSpec(root, tokenizer, "tokenizer")
    )

    fun fileSpec(root: File, path: String, role: String): LocalModelManifestFile {
        val file = root.resolve(path)
        return LocalModelManifestFile(
            path = path,
            role = role,
            size = file.length(),
            sha256 = sha256(file)
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
