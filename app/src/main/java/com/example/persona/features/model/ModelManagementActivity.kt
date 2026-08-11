package com.example.persona.features.model

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.persona.databinding.ActivityModelManagementBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ModelManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagementBinding
    private val viewModel: ModelManagementViewModel by viewModels()
    private var lastFeedbackId = 0L
    private val adapter = ModelListAdapter(
        onSelect = { item -> viewModel.selectModel(item) },
        onDelete = { item -> confirmDelete(item) },
        onDetails = { item -> showDetails(item) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvModels.layoutManager = LinearLayoutManager(this)
        binding.rvModels.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { viewModel.refreshModels(showFeedback = true) }
        binding.btnUseCloud.setOnClickListener { viewModel.useCloud() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state)
                }
            }
        }

        viewModel.refreshModels()
    }

    private fun render(state: ModelManagementUiState) = with(binding) {
        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.INVISIBLE
        btnRefresh.isEnabled = !state.isLoading
        btnRefresh.text = if (state.isLoading) "扫描中..." else "重新扫描"
        btnUseCloud.isEnabled = !state.isLoading && state.currentModelName != null
        btnUseCloud.text = if (state.currentModelName == null) "当前使用" else "改用云端"
        tvStatus.text = state.message
        tvModelsRoot.text = "模型目录：" + state.modelsRootPath
        tvRecommendation.text = state.recommendationText
        tvCurrentModel.text = currentModelText(state)
        tvEmpty.visibility = if (!state.isLoading && state.items.isEmpty()) View.VISIBLE else View.GONE
        rvModels.visibility = if (state.items.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitItems(state.items)
        state.feedbackMessage?.let { feedback ->
            if (state.feedbackId != lastFeedbackId) {
                lastFeedbackId = state.feedbackId
                Snackbar.make(root, feedback, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentModelText(state: ModelManagementUiState): String {
        val currentModelName = state.currentModelName
        if (currentModelName != null) {
            return "当前模式：本地 · $currentModelName"
        }
        return if (!state.isLoading && state.items.isNotEmpty() && state.items.none { item -> item.isReady }) {
            "当前模式：云端（没有可用的 Ready 模型）"
        } else {
            "当前模式：云端"
        }
    }

    private fun confirmDelete(item: ModelUiItem) {
        AlertDialog.Builder(this)
            .setTitle("删除模型？")
            .setMessage("将删除模型目录：\n" + item.modelDir + "\n\n如果这是当前模型，只会清空 currentModel，不会由 UI 直接释放 AI 引擎。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteModel(item)
            }
            .show()
    }

    private fun showDetails(item: ModelUiItem) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(item.detailText)
            .setPositiveButton("关闭", null)
            .show()
    }
}
