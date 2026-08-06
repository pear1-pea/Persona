package com.example.persona.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuthManagerTest {

    private val authManager = AuthManager()

    @Test
    fun `isLoggedIn returns false when there is no cached Authing user`() {
        assertFalse(authManager.isLoggedIn.value)
    }

    @Test
    fun `currentUserId returns null when not logged in`() {
        assertNull(authManager.currentUserId)
    }

    @Test
    fun `currentRepoId returns OFFLINE_USER when not logged in`() {
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }
}
