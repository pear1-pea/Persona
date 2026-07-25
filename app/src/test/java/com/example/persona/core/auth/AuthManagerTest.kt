package com.example.persona.core.auth

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthManagerTest {

    private val auth: FirebaseAuth = mock()
    private val authResult: AuthResult = mock()
    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        whenever(auth.currentUser).thenReturn(null)
        authManager = AuthManager(auth)
    }

    @Test
    fun `isLoggedIn returns false when no user`() {
        assertFalse(authManager.isLoggedIn.value)
    }

    @Test
    fun `currentUserId returns null when not logged in`() {
        assertEquals(null, authManager.currentUserId)
    }

    @Test
    fun `currentRepoId returns OFFLINE_USER when not logged in`() {
        assertEquals("OFFLINE_USER", authManager.currentRepoId)
    }

    @Test
    fun `signInAnonymously calls FirebaseAuth`() = runTest {
        val task: Task<AuthResult> = Tasks.forResult(authResult)
        whenever(auth.signInAnonymously()).thenReturn(task)

        authManager.signInAnonymously()

        verify(auth).signInAnonymously()
    }

    @Test
    fun `signIn calls signInWithEmailAndPassword`() = runTest {
        val task: Task<AuthResult> = Tasks.forResult(authResult)
        whenever(auth.signInWithEmailAndPassword("test@test.com", "password")).thenReturn(task)

        authManager.signIn("test@test.com", "password")

        verify(auth).signInWithEmailAndPassword("test@test.com", "password")
    }

    @Test
    fun `signUp calls createUserWithEmailAndPassword`() = runTest {
        val task: Task<AuthResult> = Tasks.forResult(authResult)
        whenever(auth.createUserWithEmailAndPassword("test@test.com", "password")).thenReturn(task)

        authManager.signUp("test@test.com", "password")

        verify(auth).createUserWithEmailAndPassword("test@test.com", "password")
    }

    @Test
    fun `logout signs out from Firebase`() {
        authManager.logout()
        verify(auth).signOut()
    }

    @Test
    fun `authStateListener was registered`() {
        verify(auth).addAuthStateListener(any())
    }
}
