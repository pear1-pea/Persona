package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.entity.CloudPersonaEntity
import com.example.persona.domain.model.Persona
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [CloudPersonaRepository].
 *
 * These tests verify the repository behavior against a mocked Firestore.
 * The important path to test is the error propagation in [CloudPersonaRepository.getMyPersonas].
 */
class CloudPersonaRepositoryTest {

    private val firestore: FirebaseFirestore = mock()
    private val authManager: AuthManager = mock()
    private lateinit var repo: CloudPersonaRepository

    @Before
    fun setUp() {
        whenever(authManager.currentRepoId).thenReturn("user-1")
        whenever(authManager.offlineUserId).thenReturn("OFFLINE_USER")
        whenever(authManager.isLoggedIn).thenReturn(MutableStateFlow(true))

        repo = CloudPersonaRepository(firestore, authManager)
    }

    @Test
    fun `getMyPersonas returns empty for offline user`() = runTest {
        whenever(authManager.currentRepoId).thenReturn("OFFLINE_USER")

        val result = repo.getMyPersonas()

        assertTrue(result.isEmpty())
    }
}
