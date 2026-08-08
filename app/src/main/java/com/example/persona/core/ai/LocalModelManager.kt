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
    @ApplicationContext private val context: Context,
    private val validator: LocalModelValidator
) {
    private val preferences = context.getSharedPreferences("local_model_settings", Context.MODE_PRIVATE)
    private val _currentModel = MutableStateFlow<InstalledModel?>(null)
    val currentModel: StateFlow<InstalledModel?> = _currentModel.asStateFlow()

    suspend fun scanModels(): List<ModelScanReport> = withContext(Dispatchers.IO) {
        scanModelReports()
    }

    suspend fun refreshModelReports(): List<ModelScanReport> = withContext(Dispatchers.IO) {
        val reports = scanModelReports()
        val readyModels = reports.mapNotNull { report ->
            (report.result as? ModelScanResult.Ready)?.model
        }.distinctBy(InstalledModel::id)

        val selectedModel = findSelectedModel(readyModels)
        _currentModel.value = selectedModel
        if (selectedModel == null) {
            clearCurrentModelPreference()
        }
        reports
    }

    suspend fun refreshInstalledModels(): List<InstalledModel> = withContext(Dispatchers.IO) {
        refreshModelReports().mapNotNull { report ->
            (report.result as? ModelScanResult.Ready)?.model
        }.distinctBy(InstalledModel::id)
    }

    suspend fun selectModel(modelDir: String): InstalledModel? = withContext(Dispatchers.IO) {
        val target = resolveManagedModelDirectory(modelDir) ?: return@withContext null
        val report = validator.validate(target, deviceCapability())
        val model = (report.result as? ModelScanResult.Ready)?.model ?: return@withContext null
        _currentModel.value = model
        preferences.edit()
            .putString(KEY_CURRENT_MODEL_ID, model.id)
            .putString(KEY_CURRENT_MODEL_DIR, model.modelDir)
            .apply()
        model
    }

    suspend fun clearCurrentModel() = withContext(Dispatchers.IO) {
        _currentModel.value = null
        clearCurrentModelPreference()
    }

    suspend fun deleteModel(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        val target = resolveManagedModelDirectory(modelDir) ?: return@withContext false
        val deleted = !target.exists() || target.deleteRecursively()
        if (deleted && currentModelMatches(target)) {
            _currentModel.value = null
            clearCurrentModelPreference()
        }
        deleted
    }

    fun deviceCapability(): DeviceCapability {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val root = modelsRoot().also { it.mkdirs() }
        return DeviceCapability(
            ramGb = memoryInfo.totalMem.toRoundedGb(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            sdk = Build.VERSION.SDK_INT,
            availableStorageBytes = root.usableSpace
        )
    }

    fun modelsRoot(): File {
        return checkNotNull(context.getExternalFilesDir(MODELS_DIRECTORY)) {
            "无法访问 App 专属外部 models 目录"
        }
    }

    private fun findSelectedModel(models: List<InstalledModel>): InstalledModel? {
        val selectedDir = preferences.getString(KEY_CURRENT_MODEL_DIR, null)
        val selectedId = preferences.getString(KEY_CURRENT_MODEL_ID, null)
        return models.firstOrNull { it.modelDir == selectedDir }
            ?: models.firstOrNull { it.id == selectedId }
    }

    private fun candidateModelDirectories(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter(File::isDirectory)
            .orEmpty()
    }

    private fun scanModelReports(): List<ModelScanReport> {
        val capability = deviceCapability()
        return modelsRoot()
            .also { it.mkdirs() }
            .let(::candidateModelDirectories)
            .map { directory -> validator.validate(directory, capability) }
    }

    private fun Long.toRoundedGb(): Int {
        if (this <= 0L) return 0
        return ((this + BYTES_PER_GB - 1) / BYTES_PER_GB).toInt()
    }

    private fun resolveManagedModelDirectory(modelDir: String): File? {
        val target = runCatching { File(modelDir).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { modelsRoot().canonicalFile }.getOrNull() ?: return null
        return target.takeIf { it.parentFile == root && it.isDirectory }
    }

    private fun currentModelMatches(target: File): Boolean {
        val current = _currentModel.value ?: return false
        val currentDir = runCatching { File(current.modelDir).canonicalFile }.getOrNull()
        return currentDir == target || current.id == target.name
    }

    private fun clearCurrentModelPreference() {
        preferences.edit()
            .remove(KEY_CURRENT_MODEL_ID)
            .remove(KEY_CURRENT_MODEL_DIR)
            .apply()
    }

    private companion object {
        const val KEY_CURRENT_MODEL_ID = "current_model_id"
        const val KEY_CURRENT_MODEL_DIR = "current_model_dir"
        const val MODELS_DIRECTORY = "models"
        const val MANIFEST_FILE = "manifest.json"
        const val BYTES_PER_GB = 1_000_000_000L
    }
}
