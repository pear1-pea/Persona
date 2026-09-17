package com.example.persona.core.ai

import android.system.Os
import android.system.OsConstants
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelValidator @Inject constructor() {
    private val gson = Gson()

    fun validate(
        modelDir: File,
        profile: DeviceProfile,
        requireReadyMarker: Boolean = true,
        recentFailureCount: (String) -> Int = { 0 }
    ): ModelScanReport {
        val scannedAtMillis = System.currentTimeMillis()
        val canonicalModelDir = runCatching { modelDir.canonicalFile }.getOrElse { modelDir }
        val manifestFile = if (modelDir.isSymbolicLink()) {
            null
        } else {
            canonicalModelDir.resolveSafe(MANIFEST_FILE)
        }
        val manifestRaw = manifestFile
            ?.takeIf(File::isFile)
            ?.let { file -> runCatching { file.readText() }.getOrNull() }
        val result = runCatching {
            validateInternal(
                modelDir = modelDir,
                manifestRaw = manifestRaw,
                profile = profile,
                requireReadyMarker = requireReadyMarker,
                recentFailureCount = recentFailureCount
            )
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
        manifestRaw: String?,
        profile: DeviceProfile,
        requireReadyMarker: Boolean,
        recentFailureCount: (String) -> Int
    ): ModelScanResult {
        if (modelDir.isSymbolicLink()) {
            return ModelScanResult.Corrupted("模型目录不能是 symbolic link")
        }
        val root = modelDir.canonicalFile
        if (!root.isDirectory) return ModelScanResult.NotInstalled
        if (root.walkTopDown().any { it != root && it.isSymbolicLink() }) {
            return ModelScanResult.Corrupted("模型目录不能包含 symbolic link")
        }
        val safeManifestFile = root.resolveSafe(MANIFEST_FILE)
            ?: return ModelScanResult.Corrupted("manifest.json 路径不安全")
        if (requireReadyMarker && root.resolveSafe(READY_MARKER)?.isFile != true) {
            return ModelScanResult.NotInstalled
        }
        if (!safeManifestFile.isFile || manifestRaw.isNullOrBlank()) {
            return ModelScanResult.NotInstalled
        }

        val manifest = runCatching {
            gson.fromJson(manifestRaw, LocalModelManifest::class.java)
        }.getOrNull() ?: return ModelScanResult.Corrupted("manifest.json 无法解析")

        if (manifest.schemaVersion != MANIFEST_SCHEMA_VERSION) {
            return ModelScanResult.Unsupported("不支持的 manifest schemaVersion: ${manifest.schemaVersion}")
        }
        val id = manifest.id.requireModelId()
            ?: return ModelScanResult.Corrupted("manifest 缺少或非法 id")
        val name = manifest.name.requireField()
            ?: return ModelScanResult.Corrupted("manifest 缺少 name")
        val version = manifest.version.requireField()
            ?: return ModelScanResult.Corrupted("manifest 缺少 version")
        val backendText = manifest.backend.requireField()
            ?: return ModelScanResult.Corrupted("manifest 缺少 backend")
        val entry = manifest.entry.requireRelativePath()
            ?: return ModelScanResult.Corrupted("manifest 缺少或不安全的 entry")
        val tokenizer = manifest.tokenizer.requireRelativePath()
            ?: return ModelScanResult.Corrupted("manifest 缺少或不安全的 tokenizer")
        val minSdk = manifest.minSdk
            ?.takeIf { it >= 0 }
            ?: return ModelScanResult.Corrupted("manifest 缺少或非法 minSdk")
        val minRamGb = manifest.minRamGb
            ?.takeIf { it >= 0 }
            ?: return ModelScanResult.Corrupted("manifest 缺少或非法 minRamGb")
        val family = manifest.family.normalizeManifestValue()
            ?: return ModelScanResult.Corrupted("manifest 缺少 family")
        val promptFormat = manifest.promptFormat.normalizeManifestValue()
            ?: return ModelScanResult.Corrupted("manifest 缺少 promptFormat")
        val contextWindow = manifest.contextWindow?.takeIf { it > 0 }
            ?: return ModelScanResult.Corrupted("manifest 缺少 contextWindow")
        if (contextWindow > MAX_CONTEXT_WINDOW) {
            return ModelScanResult.Unsupported("contextWindow 超出支持范围: $contextWindow")
        }
        val requiredAbis = manifest.requiredAbis.orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)

        val backend = runCatching {
            Backend.valueOf(backendText.uppercase(Locale.US))
        }.getOrNull() ?: return ModelScanResult.Unsupported("不支持的 backend: $backendText")
        if (backend != Backend.MNN) {
            return ModelScanResult.Unsupported("本地模型仅支持 MNN backend: $backendText")
        }
        if (!isSupportedPromptFormat(promptFormat)) {
            return ModelScanResult.Unsupported("不支持的 promptFormat: $promptFormat")
        }
        if (!isSupportedFamily(family)) {
            return ModelScanResult.Unsupported("不支持的 family: $family")
        }
        if (requiredAbis.isEmpty()) {
            return ModelScanResult.Corrupted("manifest 缺少 requiredAbis")
        }
        if (profile.abi !in requiredAbis) {
            return ModelScanResult.Unsupported("当前 ABI ${profile.abi} 不在 requiredAbis 中")
        }
        if (profile.sdk < minSdk) {
            return ModelScanResult.Unsupported("当前 Android SDK ${profile.sdk} 低于模型要求 $minSdk")
        }

        val configFile = root.resolveSafe(CONFIG_FILE)
            ?: return ModelScanResult.Corrupted("config.json 路径不安全")
        if (!configFile.isFile) {
            return ModelScanResult.Corrupted("缺少 $CONFIG_FILE")
        }
        val config = runCatching {
            gson.fromJson(configFile.readText(), MnnRuntimeConfig::class.java)
        }.getOrNull() ?: return ModelScanResult.Corrupted("config.json 无法解析")
        val llmModel = config.llm_model.requireRelativePath()
            ?: return ModelScanResult.Corrupted("config.json 缺少或不安全的 llm_model")
        val llmWeight = config.llm_weight.requireRelativePath()
            ?: return ModelScanResult.Corrupted("config.json 缺少或不安全的 llm_weight")
        if (llmModel != entry) {
            return ModelScanResult.Corrupted("manifest entry 与 config.json llm_model 不一致: $entry / $llmModel")
        }
        if (root.resolveSafe(llmModel)?.isFile != true) {
            return ModelScanResult.Corrupted("缺少 llm_model 文件: $llmModel")
        }
        if (root.resolveSafe(llmWeight)?.isFile != true) {
            return ModelScanResult.Corrupted("缺少 llm_weight 文件: $llmWeight")
        }

        val entryFile = root.resolveSafe(entry)
            ?: return ModelScanResult.Corrupted("entry 路径不安全")
        val tokenizerFile = root.resolveSafe(tokenizer)
            ?: return ModelScanResult.Corrupted("tokenizer 路径不安全")
        if (!entryFile.isFile) return ModelScanResult.Corrupted("缺少 entry 文件: $entry")
        if (!tokenizerFile.isFile) return ModelScanResult.Corrupted("缺少 tokenizer 文件: $tokenizer")

        validateManifestFiles(
            root = root,
            files = manifest.files,
            requiredPaths = setOf(CONFIG_FILE, entry, tokenizer, llmWeight)
        ).let { result ->
            if (result != null) return result
        }

        val modelSizeBytes = root.directorySizeBytes()
        val admission = evaluateModelAdmission(
            profile = profile,
            requiredAbis = requiredAbis,
            minSdk = minSdk,
            minRamGb = minRamGb,
            modelSizeBytes = modelSizeBytes,
            contextWindow = contextWindow,
            reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS,
            recentFailureCount = recentFailureCount(id)
        )

        return ModelScanResult.Ready(
            model = InstalledModel(
                id = id,
                name = name,
                version = version,
                modelDir = root.absolutePath,
                backend = backend,
                family = family,
                promptFormat = promptFormat,
                entry = entry,
                tokenizer = tokenizer,
                contextWindow = contextWindow,
                minRamGb = minRamGb,
                minSdk = minSdk,
                manifestPath = safeManifestFile.canonicalPath,
                requiredAbis = requiredAbis,
                totalSizeBytes = modelSizeBytes
            ),
            admission = admission
        )
    }

    private fun validateManifestFiles(
        root: File,
        files: List<LocalModelManifestFile>?,
        requiredPaths: Set<String>
    ): ModelScanResult? {
        if (files.isNullOrEmpty()) {
            return ModelScanResult.Corrupted("manifest 缺少 files 校验清单")
        }

        val seenPaths = mutableSetOf<String>()
        files.forEach { expected ->
            val path = expected.path.requireRelativePath()
                ?: return ModelScanResult.Corrupted("files.path 缺失或不安全")
            expected.role.requireField()
                ?: return ModelScanResult.Corrupted("files.role 缺失: $path")
            val expectedSize = expected.size?.takeIf { it >= 0L }
                ?: return ModelScanResult.Corrupted("files.size 缺失或非法: $path")
            val expectedHash = expected.sha256.requireField()
                ?.lowercase(Locale.US)
                ?: return ModelScanResult.Corrupted("files.sha256 缺失: $path")
            if (!SHA256_REGEX.matches(expectedHash)) {
                return ModelScanResult.Corrupted("files.sha256 格式非法: $path")
            }
            if (!seenPaths.add(path)) {
                return ModelScanResult.Corrupted("files 存在重复路径: $path")
            }
            val file = root.resolveSafe(path)
                ?: return ModelScanResult.Corrupted("files 路径不安全: $path")

            if (!file.isFile) return ModelScanResult.Corrupted("缺少文件: $path")
            if (file.length() != expectedSize) {
                return ModelScanResult.Corrupted("文件大小不匹配: $path")
            }
            if (file.sha256() != expectedHash) {
                return ModelScanResult.Corrupted("文件 SHA256 不匹配: $path")
            }
        }
        if (!requiredPaths.all(seenPaths::contains)) {
            return ModelScanResult.Corrupted("files 校验清单未覆盖 runtime 必要文件")
        }
        return null
    }

    private fun String?.requireField(): String? {
        return this?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun String?.requireModelId(): String? {
        val value = requireField() ?: return null
        return value.takeIf { MODEL_ID_REGEX.matches(it) }
    }

    private fun String?.requireRelativePath(): String? {
        val value = requireField() ?: return null
        if (File(value).isAbsolute || value.startsWith("/") || value.startsWith("\\")) return null
        val parts = value.split('/', '\\')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return value
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

    private fun File.resolveSafe(relativePath: String): File? {
        val root = canonicalFile
        val candidate = File(root, relativePath)
        if (candidate.pathComponents().any { it.isSymbolicLink() }) return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path + File.separator
        if (!canonical.path.startsWith(rootPath)) return null
        return canonical
    }

    private fun File.pathComponents(): List<File> {
        val absolute = absoluteFile
        val components = ArrayDeque<File>()
        var current: File? = absolute
        while (current != null) {
            components.addFirst(current)
            current = current.parentFile
        }
        return components
    }

    private fun File.isSymbolicLink(): Boolean {
        return runCatching {
            val mode = Os.lstat(path).st_mode
            mode and OsConstants.S_IFMT == OsConstants.S_IFLNK
        }.getOrDefault(false)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val CONFIG_FILE = "config.json"
        const val READY_MARKER = "READY"
        const val MANIFEST_SCHEMA_VERSION = 1
        const val DEFAULT_RESERVED_OUTPUT_TOKENS = 256
        const val MAX_CONTEXT_WINDOW = 1_000_000
        private val MODEL_ID_REGEX = Regex("[A-Za-z0-9._-]+")
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}
