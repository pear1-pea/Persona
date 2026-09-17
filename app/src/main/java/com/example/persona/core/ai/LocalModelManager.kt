package com.example.persona.core.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.net.Uri
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
    private val validator: LocalModelValidator,
    private val packageInstaller: ModelPackageInstaller
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
            (report.result as? ModelScanResult.Ready)
                ?.takeIf { it.admission.isSelectable }
                ?.model
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
            (report.result as? ModelScanResult.Ready)
                ?.takeIf { it.admission.isSelectable }
                ?.model
        }.distinctBy(InstalledModel::id)
    }

    suspend fun selectModel(modelDir: String): InstalledModel? = withContext(Dispatchers.IO) {
        val target = resolveManagedModelDirectory(modelDir) ?: return@withContext null
        val report = validator.validate(
            target,
            deviceCapability(),
            recentFailureCount = ::recentFailureCount
        )
        val ready = report.result as? ModelScanResult.Ready ?: return@withContext null
        if (!ready.admission.isSelectable) return@withContext null
        val model = ready.model
        clearLocalLoadFailureHistory(model.id)
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

    suspend fun installModelFromTree(uri: Uri): ModelPackageInstallResult {
        return withContext(Dispatchers.IO) {
            packageInstaller.install(
                sourceTree = uri,
                modelsRoot = modelsRoot(),
                profile = deviceCapability(),
                recentFailureCount = ::recentFailureCount
            )
        }
    }

    fun recordLocalLoadFailure(model: InstalledModel, error: Throwable? = null) {
        val key = failureKey(model.id)
        val now = System.currentTimeMillis()
        val previousFailureAt = preferences.getLong(failureTimeKey(model.id), 0L)
        val previous = if (
            previousFailureAt > 0L && now - previousFailureAt <= FAILURE_WINDOW_MILLIS
        ) {
            preferences.getInt(key, 0)
        } else {
            0
        }
        preferences.edit()
            .putInt(key, (previous + 1).coerceAtMost(MAX_RECORDED_FAILURES))
            .putLong(failureTimeKey(model.id), now)
            .putString(failureProfileKey(model.id), deviceCapability().toDiagnosticText())
            .putString(failureMessageKey(model.id), error?.message.orEmpty())
            .apply()
    }

    fun recordLocalLoadSuccess(model: InstalledModel) {
        preferences.edit()
            .remove(failureKey(model.id))
            .remove(failureTimeKey(model.id))
            .remove(failureProfileKey(model.id))
            .remove(failureMessageKey(model.id))
            .apply()
    }

    fun recentFailureCount(modelId: String): Int {
        val lastFailureAt = preferences.getLong(failureTimeKey(modelId), 0L)
        if (lastFailureAt <= 0L ||
            System.currentTimeMillis() - lastFailureAt > FAILURE_WINDOW_MILLIS
        ) {
            return 0
        }
        return preferences.getInt(failureKey(modelId), 0)
    }

    private fun clearLocalLoadFailureHistory(modelId: String) {
        preferences.edit()
            .remove(failureKey(modelId))
            .remove(failureTimeKey(modelId))
            .remove(failureProfileKey(modelId))
            .remove(failureMessageKey(modelId))
            .apply()
    }

    fun deviceCapability(): DeviceCapability {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val root = modelsRoot().also { it.mkdirs() }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val processMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemoryInfo)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra("level", -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra("scale", -1) ?: -1
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
            (batteryLevel * 100 / batteryScale).coerceIn(0, 100)
        } else {
            -1
        }
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
                else -> ThermalStatus.UNKNOWN
            }
        } else {
            ThermalStatus.UNKNOWN
        }
        return DeviceProfile(
            totalRamBytes = memoryInfo.totalMem,
            availableMemBytes = memoryInfo.availMem,
            appMemoryClassBytes = activityManager.memoryClass * 1024L * 1024L,
            nativePssBytes = processMemoryInfo.nativePss * 1024L,
            freeStorageBytes = root.usableSpace,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            sdk = Build.VERSION.SDK_INT,
            thermalStatus = thermalStatus,
            batteryPercent = batteryPercent,
            powerSaveMode = powerManager.isPowerSaveMode
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
            .filter { it.name != STAGING_DIRECTORY }
            .map { directory ->
                validator.validate(
                    directory,
                    capability,
                    recentFailureCount = ::recentFailureCount
                )
            }
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
        const val STAGING_DIRECTORY = ".staging"
        const val FAILURE_KEY_PREFIX = "local_load_failures_"
        const val FAILURE_TIME_KEY_PREFIX = "local_load_failure_time_"
        const val FAILURE_PROFILE_KEY_PREFIX = "local_load_failure_profile_"
        const val FAILURE_MESSAGE_KEY_PREFIX = "local_load_failure_message_"
        const val MAX_RECORDED_FAILURES = 3
        const val FAILURE_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
    }

    private fun failureKey(modelId: String): String = FAILURE_KEY_PREFIX + modelId

    private fun failureTimeKey(modelId: String): String = FAILURE_TIME_KEY_PREFIX + modelId

    private fun failureProfileKey(modelId: String): String = FAILURE_PROFILE_KEY_PREFIX + modelId

    private fun failureMessageKey(modelId: String): String = FAILURE_MESSAGE_KEY_PREFIX + modelId

    private fun DeviceProfile.toDiagnosticText(): String {
        return "ram=$totalRamBytes,availableMem=$availableMemBytes," +
            "appMemoryClass=$appMemoryClassBytes,nativePss=$nativePssBytes," +
            "storage=$freeStorageBytes,abi=$abi,sdk=$sdk," +
            "thermal=$thermalStatus,battery=$batteryPercent,powerSave=$powerSaveMode"
    }
}
