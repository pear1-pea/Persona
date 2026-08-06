package com.example.persona

import android.app.Application
import cn.authing.guard.Authing
import com.example.persona.core.util.SettingsManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PersonaApp : Application() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()

        Authing.init(this, BuildConfig.AUTHING_APP_ID)
        Authing.setAuthProtocol(Authing.AuthProtocol.EOIDC)

        // Read saved settings and apply theme on startup
        val savedMode = settingsManager.getThemeMode()
        settingsManager.applyTheme(savedMode)
    }
}
