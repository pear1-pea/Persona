package com.example.persona.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsManager].
 *
 * SettingsManager requires [android.content.Context] for SharedPreferences,
 * which cannot be provided in unit tests without Robolectric or a mocked Context.
 * These tests verify the key/flag logic is sound.
 */
class SettingsManagerTest {

    @Test
    fun `theme mode constants are correct`() {
        assertEquals(-1, SettingsManager.THEME_SYSTEM)
        assertEquals(1, SettingsManager.THEME_LIGHT)
        assertEquals(2, SettingsManager.THEME_DARK)
    }

    @Test
    fun `theme mode values cover all states`() {
        val modes = listOf(SettingsManager.THEME_SYSTEM, SettingsManager.THEME_LIGHT, SettingsManager.THEME_DARK)
        assertEquals(3, modes.distinct().size)
    }
}
