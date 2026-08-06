package com.example.persona.features.download

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.core.ai.RecommendedModel
import com.example.persona.databinding.ActivityDownloadModelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DownloadModelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadModelBinding
    private val viewModel: DownloadModelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadModelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSkipDownload.setOnClickListener {
            finish()
        }

        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.btnSkipDownload.text = "\u8fd4\u56de"

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.tvStatus.text = if (state.isLoading) {
                        "\u6b63\u5728\u68c0\u67e5\u672c\u5730\u6a21\u578b..."
                    } else {
                        buildStatusText(state)
                    }
                }
            }
        }

        viewModel.refreshModels()
    }

    private fun buildStatusText(state: ModelManagementUiState): String {
        val recommendation = when (state.recommendedModel) {
            RecommendedModel.QWEN_1_5B -> "\u63a8\u8350\u4f53\u9a8c Qwen2.5-1.5B-Instruct-MNN\uff0c\u4e5f\u53ef\u4ee5\u7528 0.5B \u9a8c\u8bc1\u6d41\u7a0b\u3002"
            RecommendedModel.QWEN_0_5B -> "\u63a8\u8350\u5148\u4f7f\u7528 Qwen2.5-0.5B-Instruct-MNN\u3002"
            RecommendedModel.CLOUD_ONLY -> "\u5f53\u524d\u8bbe\u5907\u5efa\u8bae\u5148\u4f7f\u7528\u4e91\u7aef AI\uff0c\u672c\u5730\u6a21\u578b\u53ef\u80fd\u5185\u5b58\u4e0d\u8db3\u3002"
        }
        val selected = state.selectedModel?.let { "\n\u5f53\u524d\u9009\u62e9: " + it.name }.orEmpty()
        val modelsRoot = if (state.modelsRootPath.isNotBlank()) {
            "\n\u6a21\u578b\u76ee\u5f55: " + state.modelsRootPath
        } else {
            ""
        }
        val installed = if (state.installedModels.isEmpty()) {
            "\n\u8bf7\u624b\u52a8\u628a MNN \u6a21\u578b\u653e\u5230 App \u4e13\u5c5e models \u76ee\u5f55\u540e\u91cd\u65b0\u6253\u5f00\u6b64\u9875\u3002"
        } else {
            "\n\u5df2\u5b89\u88c5: " + state.installedModels.joinToString { it.name }
        }
        return listOf(state.message, recommendation).filter { it.isNotBlank() }.joinToString("\n") +
            selected +
            modelsRoot +
            installed
    }
}
