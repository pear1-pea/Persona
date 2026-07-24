package com.example.persona.features.download

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.MainActivity
import com.example.persona.core.ai.DownloadState
import com.example.persona.core.util.SettingsManager
import com.example.persona.databinding.ActivityDownloadModelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadModelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadModelBinding
    private val viewModel: DownloadModelViewModel by viewModels()

    @Inject
    lateinit var settingsManager: SettingsManager

    companion object {
        // Replace with actual CDN URL before release
        private const val MODEL_URL = "https://your-cdn.example.com/gemma-2b-it-cpu-int4.bin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadModelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSkipDownload.setOnClickListener {
            settingsManager.setEdgeModelSkipped(true)
            goToMain()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is DownloadState.Progress -> {
                            binding.progressBar.progress = state.percent
                            binding.tvProgress.text = "${state.percent}%"
                        }
                        is DownloadState.Success -> {
                            binding.tvStatus.text = "下载完成"
                            binding.btnSkipDownload.visibility = View.GONE
                            goToMain()
                        }
                        is DownloadState.Failure -> {
                            binding.tvStatus.text = "下载失败: ${state.message}"
                            binding.btnSkipDownload.text = "继续（使用云端 AI）"
                        }
                    }
                }
            }
        }

        viewModel.startDownload(MODEL_URL)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
