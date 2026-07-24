package com.example.persona.features.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.persona.MainActivity
import com.example.persona.R
import com.example.persona.core.ai.ModelDownloadManager
import com.example.persona.core.util.SettingsManager
import com.example.persona.features.download.DownloadModelActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuthActivity : AppCompatActivity(R.layout.activity_auth) {

    private val viewModel: AuthViewModel by viewModels()

    @Inject lateinit var modelDownloadManager: ModelDownloadManager
    @Inject lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoggedIn.collect { isLoggedIn ->
                    if (isLoggedIn) {
                        navigateAfterLogin()
                    }
                }
            }
        }
    }

    private fun navigateAfterLogin() {
        val needsDownload = !modelDownloadManager.isModelAvailable()
            && !settingsManager.isEdgeModelSkipped()
        val intent = if (needsDownload) {
            Intent(this, DownloadModelActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
