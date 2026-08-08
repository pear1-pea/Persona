package com.example.persona.features.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.persona.core.ai.DeviceCapability
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.ModelScanReport
import com.example.persona.core.ai.ModelScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ModelStatusKind {
    Ready,
    Current,
    NotInstalled,
    Corrupted,
    Unsupported,
    Failed
}

data class ModelUiItem(
    val directoryName: String,
    val name: String,
    val version: String,
    val backend: String,
    val family: String,
    val promptFormat: String,
    val contextWindow: String,
    val modelDir: String,
    val statusLabel: String,
    val statusKind: ModelStatusKind,
    val reason: String?,
    val riskText: String?,
    val isReady: Boolean,
    val isCurrent: Boolean,
    val minRamGb: Int,
    val fileSize: String,
    val lastScannedText: String,
    val manifestText: String,
    val detailText: String
)

data class ModelManagementUiState(
    val isLoading: Boolean = true,
    val items: List<ModelUiItem> = emptyList(),
    val modelsRootPath: String = "",
    val recommendationText: String = "",
    val currentModelName: String? = null,
    val message: String = "",
    val feedbackId: Long = 0L,
    val feedbackMessage: String? = null
)

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val localModelManager: LocalModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(ModelManagementUiState())
    val state: StateFlow<ModelManagementUiState> = _state.asStateFlow()
    private var nextFeedbackId = 1L

    fun refreshModels(message: String? = null, showFeedback: Boolean = false) {
        viewModelScope.launch {
            val previousSignatures = _state.value.items.map { item -> item.stableSignature() }
            _state.value = _state.value.copy(isLoading = true, message = message ?: "正在扫描本地模型...")
            loadModels(
                message = message,
                showScanFeedback = showFeedback,
                previousSignatures = previousSignatures
            )
        }
    }

    fun selectModel(item: ModelUiItem) {
        if (!item.isReady) {
            _state.value = _state.value.copy(message = "该模型还不可用：" + (item.reason ?: item.statusLabel))
            return
        }

        viewModelScope.launch {
            if (item.isCurrent) {
                _state.value = _state.value.copy(message = "该模型已是当前使用")
            } else {
                _state.value = _state.value.copy(isLoading = true, message = "正在切换模型...")
                val selected = localModelManager.selectModel(item.modelDir)
                loadModels(
                    if (selected != null) {
                        buildString {
                            append("已切换当前模型：")
                            append(selected.name)
                            item.riskText?.let { risk ->
                                append("。")
                                append(risk)
                            }
                        }
                    } else {
                        "切换失败：模型不可用或目录不受管理"
                    }
                )
            }
        }
    }

    fun useCloud() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = "正在切换到云端...")
            localModelManager.clearCurrentModel()
            loadModels("已改用云端，聊天将默认走云端")
        }
    }

    fun deleteModel(item: ModelUiItem) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = "正在删除模型...")
            val deleted = localModelManager.deleteModel(item.modelDir)
            loadModels(
                if (deleted) {
                    "已删除模型目录：" + item.directoryName
                } else {
                    "删除失败：目录不存在或不在 App models 目录下"
                }
            )
        }
    }

    private suspend fun loadModels(
        message: String? = null,
        showScanFeedback: Boolean = false,
        previousSignatures: List<String> = emptyList()
    ) {
        val capability = localModelManager.deviceCapability()
        runCatching {
            localModelManager.refreshModelReports()
        }.onSuccess { reports ->
            val currentModel = localModelManager.currentModel.value
            val items = reports
                .map { report -> report.toUiItem(currentModel, capability) }
                .sortedWith(compareBy<ModelUiItem> { !it.isCurrent }.thenBy { it.name.lowercase(Locale.US) })

            val isUnchangedScan = showScanFeedback && previousSignatures == items.map { it.stableSignature() }
            val displayedItems = if (isUnchangedScan) _state.value.items else items
            val feedbackMessage = if (showScanFeedback) {
                if (isUnchangedScan) {
                    "扫描完成，未发现变化"
                } else {
                    "扫描成功，已更新模型列表"
                }
            } else {
                null
            }

            _state.value = ModelManagementUiState(
                isLoading = false,
                items = displayedItems,
                modelsRootPath = localModelManager.modelsRoot().absolutePath,
                recommendationText = capability.toManagementSummaryText(),
                currentModelName = currentModel?.name,
                message = message ?: defaultMessage(displayedItems),
                feedbackId = feedbackMessage?.let { nextFeedbackId++ } ?: _state.value.feedbackId,
                feedbackMessage = feedbackMessage
            )
        }.onFailure { error ->
            val feedbackMessage = if (showScanFeedback) "模型扫描失败：" + (error.message ?: "未知错误") else null
            _state.value = ModelManagementUiState(
                isLoading = false,
                items = _state.value.items,
                modelsRootPath = localModelManager.modelsRoot().absolutePath,
                recommendationText = capability.toManagementSummaryText(),
                currentModelName = _state.value.currentModelName,
                message = "模型扫描失败：" + (error.message ?: "未知错误"),
                feedbackId = feedbackMessage?.let { nextFeedbackId++ } ?: _state.value.feedbackId,
                feedbackMessage = feedbackMessage
            )
        }
    }

    private fun ModelScanReport.toUiItem(
        currentModel: InstalledModel?,
        capability: DeviceCapability
    ): ModelUiItem {
        val scanResult = result
        val readyModel = (scanResult as? ModelScanResult.Ready)?.model
        val isCurrent = readyModel != null && currentModel?.modelDir == readyModel.modelDir
        val statusKind = when (scanResult) {
            is ModelScanResult.Ready -> if (isCurrent) ModelStatusKind.Current else ModelStatusKind.Ready
            ModelScanResult.NotInstalled -> ModelStatusKind.NotInstalled
            is ModelScanResult.Corrupted -> ModelStatusKind.Corrupted
            is ModelScanResult.Unsupported -> ModelStatusKind.Unsupported
            is ModelScanResult.Failed -> ModelStatusKind.Failed
        }
        val reason = when (scanResult) {
            is ModelScanResult.Corrupted -> scanResult.reason
            is ModelScanResult.Unsupported -> scanResult.reason
            is ModelScanResult.Failed -> scanResult.reason
            ModelScanResult.NotInstalled -> "缺少 manifest.json"
            is ModelScanResult.Ready -> null
        }
        val name = readyModel?.name ?: directoryName
        val version = readyModel?.version ?: "-"
        val backend = readyModel?.backend?.name ?: "-"
        val family = readyModel?.family ?: "-"
        val promptFormat = readyModel?.promptFormat ?: "-"
        val contextWindow = readyModel?.contextWindow?.toString() ?: "-"
        val minRamGb = readyModel?.minRamGb ?: 0
        val riskText = readyModel?.memoryRiskText(capability)
        val manifestText = manifestRaw?.trim()?.takeIf(String::isNotEmpty) ?: "未读取到 manifest.json"
        val item = ModelUiItem(
            directoryName = directoryName,
            name = name,
            version = version,
            backend = backend,
            family = family,
            promptFormat = promptFormat,
            contextWindow = contextWindow,
            modelDir = modelDir,
            statusLabel = statusKind.toLabel(),
            statusKind = statusKind,
            reason = reason,
            riskText = riskText,
            isReady = readyModel != null,
            isCurrent = isCurrent,
            minRamGb = minRamGb,
            fileSize = totalSizeBytes.toReadableSize(),
            lastScannedText = scannedAtMillis.toReadableTime(),
            manifestText = manifestText,
            detailText = ""
        )
        return item.copy(detailText = item.buildDetailText())
    }

    private fun ModelUiItem.buildDetailText(): String {
        val manifestPreview = if (manifestText.length > MAX_MANIFEST_DETAIL_CHARS) {
            manifestText.take(MAX_MANIFEST_DETAIL_CHARS) + "\n..."
        } else {
            manifestText
        }
        return buildString {
            appendLine("模型名称：" + name)
            appendLine("版本：" + version)
            appendLine("Backend：" + backend)
            appendLine("Family：" + family)
            appendLine("Prompt Format：" + promptFormat)
            appendLine("Context Window：" + contextWindow)
            appendLine("建议 RAM：" + if (minRamGb > 0) "${minRamGb}GB" else "-")
            appendLine("模型路径：" + modelDir)
            appendLine("状态：" + statusLabel)
            appendLine("问题原因：" + (reason ?: "无"))
            appendLine("风险提示：" + (riskText ?: "无"))
            appendLine("文件大小：" + fileSize)
            appendLine("最后扫描时间：" + lastScannedText)
            appendLine()
            appendLine("Manifest：")
            append(manifestPreview)
        }
    }

    private fun ModelStatusKind.toLabel(): String {
        return when (this) {
            ModelStatusKind.Ready -> "Ready"
            ModelStatusKind.Current -> "当前模型"
            ModelStatusKind.NotInstalled -> "NotInstalled"
            ModelStatusKind.Corrupted -> "Corrupted"
            ModelStatusKind.Unsupported -> "Unsupported"
            ModelStatusKind.Failed -> "Failed"
        }
    }

    private fun DeviceCapability.toManagementSummaryText(): String {
        val deviceText = "设备：RAM ${ramGb}GB · ABI $abi · SDK $sdk · 模型目录可用 ${availableStorageBytes.toReadableSize()}"
        val recommendationText = when {
            abi != REQUIRED_MNN_ABI -> "推荐：云端兜底。当前 ABI 不支持 MNN 本地模型。"
            ramGb >= 8 -> "推荐：Qwen2.5 1.5B。设备内存较充足，也可用 0.5B 验证流程。"
            ramGb >= 4 -> "推荐：Qwen2.5 0.5B。当前内存不默认推荐 1.5B，但仍可手动选择并自行承担加载慢或闪退风险。"
            else -> "推荐：云端兜底。当前 RAM 低于 4GB，不建议直接跑本地大模型。"
        }
        return deviceText + "\n" + recommendationText
    }

    private fun InstalledModel.memoryRiskText(capability: DeviceCapability): String? {
        if (minRamGb <= 0 || capability.ramGb >= minRamGb) return null
        return "风险提示：模型建议 ${minRamGb}GB RAM，当前约 ${capability.ramGb}GB，可能加载慢或闪退。"
    }

    private fun defaultMessage(items: List<ModelUiItem>): String {
        return if (items.isEmpty()) {
            "未检测到模型目录。请把模型放到 App 专属 models 目录下。"
        } else {
            "已扫描 " + items.size + " 个模型目录。"
        }
    }

    private fun ModelUiItem.stableSignature(): String {
        return listOf(
            directoryName,
            name,
            version,
            backend,
            family,
            promptFormat,
            contextWindow,
            modelDir,
            statusLabel,
            statusKind.name,
            reason.orEmpty(),
            riskText.orEmpty(),
            isReady.toString(),
            isCurrent.toString(),
            minRamGb.toString(),
            fileSize,
            manifestText
        ).joinToString("|")
    }

    private fun Long.toReadableSize(): String {
        if (this <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var size = this.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.lastIndex) {
            size /= 1024.0
            unitIndex += 1
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    private fun Long.toReadableTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(this))
    }

    private companion object {
        const val MAX_MANIFEST_DETAIL_CHARS = 3000
        const val REQUIRED_MNN_ABI = "arm64-v8a"
    }
}
