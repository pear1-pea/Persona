package com.example.persona.core.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionCleanupInstrumentedTest {
    private val preferenceName = "p1-auth-test-${UUID.randomUUID()}"
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(preferenceName, mode)
        }
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun logoutPreservesPendingCleanupAcrossStoreRecreation() {
        val store = SessionTokenStore(context)
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferences.edit().putString("token", "test-only")
            .putString("user_id", "a").putString("email", "a@test.dev").commit()
        store.markAccountDeleted("a")
        store.clear()
        assertNull(preferences.getString("token", null))
        assertNull(preferences.getString("user_id", null))
        assertNull(preferences.getString("email", null))
        assertEquals(setOf("a"), SessionTokenStore(context).pendingDeletedOwners())
        SessionTokenStore(context).completeAccountDeletion("a")
        assertEquals(emptySet<String>(), store.pendingDeletedOwners())
    }

    @Test
    fun offlineNamespaceCannotBeScheduledForAccountDeletion() {
        val store = SessionTokenStore(context)
        assertTrue(runCatching { store.markAccountDeleted("OFFLINE_USER") }
            .exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptySet<String>(), store.pendingDeletedOwners())
    }
}
