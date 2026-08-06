package com.example.persona.core.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences = context.getSharedPreferences("local_model_settings", Context.MODE_PRIVATE)
    private val _currentModel = MutableStateFlow<InstalledModel?>(null)
    val currentModel: StateFlow<InstalledModel?> = _currentModel.asStateFlow()

    suspend fun refreshInstalledModels(): List<InstalledModel> = withContext(Dispatchers.IO) {
        val models = modelRoots()
            .onEach { it.mkdirs() }
            .flatMap(::candidateModelDirectories)
            .mapNotNull(::toInstalledModel)
            // The external directory comes first, so it wins if the same model
            // was copied to both locations during development.
            .distinctBy(InstalledModel::id)

        val compatibleModels = models.filter(::isCompatible)

        val selectedId = preferences.getString(KEY_CURRENT_MODEL_ID, null)
        _currentModel.value = compatibleModels.firstOrNull { it.id == selectedId } ?: compatibleModels.firstOrNull()
        _currentModel.value?.let { model ->
            preferences.edit().putString(KEY_CURRENT_MODEL_ID, model.id).apply()
        }
        models
    }

    suspend fun selectModel(modelId: String): InstalledModel? = withContext(Dispatchers.IO) {
        val model = modelRoots()
            .asSequence()
            .flatMap { root -> candidateModelDirectories(root).asSequence() }
            .firstOrNull { it.isDirectory && it.name == modelId }
            ?.let(::toInstalledModel)
            ?.takeIf(::isCompatible)

        _currentModel.value = model
        preferences.edit().putString(KEY_CURRENT_MODEL_ID, model?.id).apply()
        model
    }

    fun recommendedModel(): RecommendedModel {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / BYTES_PER_GB

        return when {
            totalRamGb >= 8 -> RecommendedModel.QWEN_1_5B
            totalRamGb >= 4 -> RecommendedModel.QWEN_0_5B
            else -> RecommendedModel.CLOUD_ONLY
        }
    }

    fun modelsRoot(): File {
        return context.getExternalFilesDir(MODELS_DIRECTORY)
            ?: File(context.filesDir, MODELS_DIRECTORY)
    }

    private fun modelRoots(): List<File> {
        return listOfNotNull(
            context.getExternalFilesDir(MODELS_DIRECTORY),
            File(context.filesDir, MODELS_DIRECTORY)
        ).distinctBy(File::getAbsolutePath)
    }

    private fun toInstalledModel(directory: File): InstalledModel? {
        val configFile = File(directory, MNN_CONFIG_FILE)
        if (!configFile.isFile) return null
        if (!containsMnnRuntimeFile(directory)) return null

        return InstalledModel(
            id = directory.name,
            name = directory.name.replace('-', ' '),
            version = "local",
            modelDir = directory.absolutePath,
            backend = Backend.MNN
        )
    }

    private fun isCompatible(model: InstalledModel): Boolean {
        return supportsArm64() &&
            Build.VERSION.SDK_INT >= MINIMUM_MNN_SDK &&
            getTotalRamGb() >= minimumRamGbFor(model)
    }

    private fun minimumRamGbFor(model: InstalledModel): Int {
        return if (model.id.contains("1.5b", ignoreCase = true)) 8 else 4
    }

    private fun getTotalRamGb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / BYTES_PER_GB
    }

    private fun supportsArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun candidateModelDirectories(root: File): List<File> {
        if (!root.exists()) return emptyList()

        val candidates = linkedSetOf<File>()
        fun scan(directory: File, depth: Int) {
            if (depth > MAX_MODEL_DIRECTORY_DEPTH || !directory.isDirectory) return

            if (File(directory, MNN_CONFIG_FILE).isFile) {
                candidates += directory
                return
            }

            directory.listFiles()
                ?.filter(File::isDirectory)
                ?.forEach { child -> scan(child, depth + 1) }
        }

        scan(root, depth = 0)
        return candidates.toList()
    }

    private fun containsMnnRuntimeFile(directory: File): Boolean {
        return directory.listFiles()
            ?.any { file ->
                file.isFile && (
                    file.extension.equals(MNN_EXTENSION, ignoreCase = true) ||
                        file.name.endsWith(MNN_WEIGHT_SUFFIX, ignoreCase = true)
                    )
            } == true
    }

    private companion object {
        const val KEY_CURRENT_MODEL_ID = "current_model_id"
        const val MODELS_DIRECTORY = "models"
        const val MNN_CONFIG_FILE = "config.json"
        const val MNN_EXTENSION = "mnn"
        const val MNN_WEIGHT_SUFFIX = ".mnn.weight"
        const val MAX_MODEL_DIRECTORY_DEPTH = 4
        const val MINIMUM_MNN_SDK = 26
        const val BYTES_PER_GB = 1024L * 1024L * 1024L
    }
}
