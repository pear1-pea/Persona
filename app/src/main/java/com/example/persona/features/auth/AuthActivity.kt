package com.example.persona.features.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.authing.guard.activity.AuthActivity as GuardAuthActivity
import cn.authing.guard.flow.AuthFlow
import com.example.persona.MainActivity
import com.example.persona.R
import com.example.persona.core.auth.AuthManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuthActivity : AppCompatActivity(R.layout.activity_auth) {

    @Inject lateinit var authManager: AuthManager

    private var authFlowLaunched = false
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAndContinue()
    }

    override fun onResume() {
        super.onResume()
        if (!navigating && !authManager.isLoggedIn.value && !authFlowLaunched) {
            launchAuthFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GuardAuthActivity.RC_LOGIN) {
            if (resultCode == GuardAuthActivity.OK) {
                refreshAndContinue()
            } else {
                authFlowLaunched = false
            }
        }
    }

    private fun refreshAndContinue() {
        if (navigating) return
        lifecycleScope.launch {
            authManager.refreshCurrentUser()
            if (authManager.isLoggedIn.value) {
                navigating = true
                navigateAfterLogin()
            } else {
                launchAuthFlow()
            }
        }
    }

    private fun launchAuthFlow() {
        if (navigating || authFlowLaunched) return
        authFlowLaunched = true
        AuthFlow.start(this)
    }

    private fun navigateAfterLogin() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}