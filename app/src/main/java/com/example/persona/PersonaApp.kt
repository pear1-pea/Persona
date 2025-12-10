package com.example.persona

import android.app.Application
import com.example.persona.core.util.SettingsManager
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.initialize
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PersonaApp : Application() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()

        Firebase.initialize(this)

        // Read saved settings and apply theme on startup
        val savedMode = settingsManager.getThemeMode()
        settingsManager.applyTheme(savedMode)
    }
}