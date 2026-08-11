package com.example.persona.core.ai

import com.google.gson.Gson
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelValidator @Inject constructor() {
    private val gson = Gson()

    fun validate(modelDir: File, capability: DeviceCapability): ModelScanReport {
        val scannedAtMillis = System.currentTimeMillis()
        val manifestFile = File(modelDir, MANIFEST_FILE)
        val manifestRaw = manifestFile.takeIf(File::isFile)?.readText()
        val result = runCatching {
            validateInternal(modelDir, manifestFile, manifestRaw, capability)
        }.getOrElse { error ->
            ModelScanResult.Failed(error.message ?: "模型校验失败")
        }

        return ModelScanReport(
            directoryName = modelDir.name,
            modelDir = modelDir.absolutePath,
            result = result,
            totalSizeBytes = modelDir.directorySizeBytes(),
            scannedAtMillis = scannedAtMillis,
            manifestRaw = manifestRaw
        )
    }

    private fun validateInternal(
        modelDir: File,
        manifestFile: File,
        manifestRaw: String?,
        capability: DeviceCapability
    ): ModelScanResult {
        if (!manifestFile.isFile || manifestRaw.isNullOrBlank()) {
            return ModelScanResult.NotInstalled
        }

        val manifest = runCatching {
            gson.fromJson(manifestRaw, LocalModelManifest::class.java)
        }.getOrNull() ?: return ModelScanResult.Corrupted("manifest.json 无法解析")

        val id = manifest.id.requireField("id")
            ?: return ModelScanResult.Corrupted("manifest 缺少 id")
        val name = manifest.name.requireField("name")
            ?: return ModelScanResult.Corrupted("manifest 缺少 name")
        val version = manifest.version.requireField("version")
            ?: return ModelScanResult.Corrupted("manifest 缺少 version")
        val backendText = manifest.backend.requireField("backend")
            ?: return ModelScanResult.Corrupted("manifest 缺少 backend")
        val entry = manifest.entry.requireField("entry")
            ?: return ModelScanResult.Corrupted("manifest 缺少 entry")
        val tokenizer = manifest.tokenizer.requireField("tokenizer")
            ?: return ModelScanResult.Corrupted("manifest 缺少 tokenizer")
        val minSdk = manifest.minSdk
            ?: return ModelScanResult.Corrupted("manifest 缺少 minSdk")
        val minRamGb = manifest.minRamGb
            ?: return ModelScanResult.Corrupted("manifest 缺少 minRamGb")
        val family = manifest.family
            .normalizeManifestValue()
            ?: return ModelScanResult.Corrupted("manifest 缺少 family")
        val promptFormat = manifest.promptFormat
            .normalizeManifestValue()
            ?: return ModelScanResult.Corrupted("manifest 缺少 promptFormat")
        val contextWindow = manifest.contextWindow?.takeIf { it > 0 }
            ?: return ModelScanResult.Corrupted("manifest 缺少 contextWindow")

        val backend = runCatching {
            Backend.valueOf(backendText.uppercase(Locale.US))
        }.getOrNull() ?: return ModelScanResult.Unsupported("不支持的 backend: $backendText")

        if (!isSupportedPromptFormat(promptFormat)) {
            return ModelScanResult.Unsupported("不支持的 promptFormat: $promptFormat")
        }

        if (!isSupportedFamily(family)) {
            return ModelScanResult.Unsupported("不支持的 family: $family")
        }

        if (backend == Backend.MNN && capability.abi != REQUIRED_MNN_ABI) {
            return ModelScanResult.Unsupported("当前 ABI ${capability.abi} 不支持 MNN，需要 $REQUIRED_MNN_ABI")
        }

        if (capability.sdk < minSdk) {
            return ModelScanResult.Unsupported("当前 Android SDK ${capability.sdk} 低于模型要求 $minSdk")
        }

        val configFile = File(modelDir, CONFIG_FILE)
        if (!configFile.isFile) {
            return ModelScanResult.Corrupted("缺少 $CONFIG_FILE")
        }

        if (backend == Backend.MNN) {
            validateMnnRuntimeFiles(modelDir, configFile, entry)?.let { result ->
                return result
            }
        }

        if (!File(modelDir, entry).isFile) {
            return ModelScanResult.Corrupted("缺少 entry 文件: $entry")
        }

        if (!File(modelDir, tokenizer).isFile) {
            return ModelScanResult.Corrupted("缺少 tokenizer 文件: $tokenizer")
        }

        return ModelScanResult.Ready(
            InstalledModel(
                id = id,
                name = name,
                version = version,
                modelDir = modelDir.absolutePath,
                backend = backend,
                family = family,
                promptFormat = promptFormat,
                entry = entry,
                tokenizer = tokenizer,
                contextWindow = contextWindow,
                minRamGb = minRamGb,
                minSdk = minSdk,
                manifestPath = manifestFile.absolutePath
            )
        )
    }

    private fun String?.requireField(fieldName: String): String? {
        return this?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun String?.normalizeManifestValue(): String? {
        return this?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.US)
    }

    private fun isSupportedPromptFormat(promptFormat: String): Boolean {
        return promptFormat == PromptFormats.QWEN_CHATML_TEXT ||
            promptFormat == PromptFormats.MNN_CHAT_MESSAGES
    }

    private fun isSupportedFamily(family: String): Boolean {
        return family == ModelFamilies.QWEN2_5
    }

    private fun validateMnnRuntimeFiles(
        modelDir: File,
        configFile: File,
        manifestEntry: String
    ): ModelScanResult? {
        val config = runCatching {
            gson.fromJson(configFile.readText(), MnnRuntimeConfig::class.java)
        }.getOrNull() ?: return ModelScanResult.Corrupted("config.json 无法解析")

        val llmModel = config.llm_model.requireField("llm_model")
            ?: return ModelScanResult.Corrupted("config.json 缺少 llm_model")
        val llmWeight = config.llm_weight.requireField("llm_weight")
            ?: return ModelScanResult.Corrupted("config.json 缺少 llm_weight")

        if (llmModel != manifestEntry) {
            return ModelScanResult.Corrupted("manifest entry 与 config.json llm_model 不一致: $manifestEntry / $llmModel")
        }

        if (!File(modelDir, llmModel).isFile) {
            return ModelScanResult.Corrupted("缺少 llm_model 文件: $llmModel")
        }

        if (!File(modelDir, llmWeight).isFile) {
            return ModelScanResult.Corrupted("缺少 llm_weight 文件: $llmWeight")
        }

        return null
    }

    private fun File.directorySizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private data class MnnRuntimeConfig(
        val llm_model: String? = null,
        val llm_weight: String? = null
    )

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val CONFIG_FILE = "config.json"
        const val REQUIRED_MNN_ABI = "arm64-v8a"
    }
}
