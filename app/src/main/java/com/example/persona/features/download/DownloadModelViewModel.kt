package com.example.persona.features.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.persona.core.ai.InstalledModel
import com.example.persona.core.ai.LocalModelManager
import com.example.persona.core.ai.RecommendedModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagementUiState(
    val isLoading: Boolean = true,
    val installedModels: List<InstalledModel> = emptyList(),
    val recommendedModel: RecommendedModel = RecommendedModel.CLOUD_ONLY,
    val selectedModel: InstalledModel? = null,
    val modelsRootPath: String = "",
    val message: String = ""
)

@HiltViewModel
class DownloadModelViewModel @Inject constructor(
    private val localModelManager: LocalModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(ModelManagementUiState())
    val state: StateFlow<ModelManagementUiState> = _state.asStateFlow()

    fun refreshModels() {
        viewModelScope.launch {
            val recommended = localModelManager.recommendedModel()
            runCatching {
                localModelManager.refreshInstalledModels()
            }.onSuccess { models ->
                val selectedModel = localModelManager.currentModel.value
                _state.value = ModelManagementUiState(
                    isLoading = false,
                    installedModels = models,
                    recommendedModel = recommended,
                    selectedModel = selectedModel,
                    modelsRootPath = localModelManager.modelsRoot().absolutePath,
                    message = if (models.isEmpty()) {
                        "\u672a\u68c0\u6d4b\u5230\u672c\u5730 MNN \u6a21\u578b\uff0c\u5f53\u524d\u4f1a\u7ee7\u7eed\u4f7f\u7528\u4e91\u7aef AI\u3002"
                    } else {
                        "\u5df2\u68c0\u6d4b\u5230 " + models.size + " \u4e2a\u672c\u5730\u6a21\u578b\u3002"
                    }
                )
            }.onFailure { error ->
                _state.value = ModelManagementUiState(
                    isLoading = false,
                    recommendedModel = recommended,
                    modelsRootPath = localModelManager.modelsRoot().absolutePath,
                    message = "\u6a21\u578b\u68c0\u67e5\u5931\u8d25: " + (error.message ?: "\u672a\u77e5\u9519\u8bef")
                )
            }
        }
    }
}
