package com.example.persona.core.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_THEME_MODE = "theme_mode"

        const val THEME_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        const val THEME_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
        const val THEME_DARK = AppCompatDelegate.MODE_NIGHT_YES
    }

    fun saveThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
    }

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}