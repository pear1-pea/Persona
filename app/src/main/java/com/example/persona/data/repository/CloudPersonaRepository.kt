package com.example.persona.data.repository

import com.example.persona.core.auth.AuthManager
import com.example.persona.data.remote.entity.CloudPersonaEntity
import com.example.persona.data.remote.entity.toCloudEntity
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import android.util.Log
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudPersonaRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authManager: AuthManager
) : PersonaRepository {

    private val personasCollection = "personas"

    override suspend fun getPersonas(): List<Persona> {
        return try {
            firestore.collection(personasCollection)
                .whereEqualTo("isPublic", true)
                .get()
                .await()
                .documents
                .mapNotNull { 
                    try {
                        it.toObject(CloudPersonaEntity::class.java)?.toDomain()
                    } catch (e: Exception) {
                        Log.e("CloudPersonaRepo", "Error parsing persona document: ${it.id}", e)
                        Firebase.crashlytics.recordException(e)
                        null
                    }
                }
        } catch (e: Exception) {
            Log.e("CloudPersonaRepo", "Error fetching personas", e)
            Firebase.crashlytics.recordException(e)
            Firebase.crashlytics.log("Failed to fetch personas: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMyPersonas(): List<Persona> {
        val currentUserId = authManager.currentRepoId
        if (currentUserId == authManager.offlineUserId) {
            return emptyList()
        }

        return try {
            firestore.collection(personasCollection)
                .whereEqualTo("creatorId", currentUserId)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(CloudPersonaEntity::class.java) }
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("CloudPersonaRepo", "Error fetching my personas", e)
            Firebase.crashlytics.recordException(e)
            throw e
        }
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String) {
        val currentUserId = authManager.currentRepoId
        if (currentUserId == authManager.offlineUserId) {
            throw IllegalStateException("User must be logged in to create cloud persona.")
        }

        val newId = UUID.randomUUID().toString()
        val newPersona = Persona(
            id = newId,
            name = name,
            avatarUrl = "https://api.dicebear.com/7.x/bottts/png?seed=$name",
            postImageUrl = "https://picsum.photos/seed/$newId/800/600",
            traits = traits,
            backstory = backstory,
            creatorId = currentUserId
        )

        val entity = newPersona.toCloudEntity()

        firestore.collection(personasCollection)
            .document(newId)
            .set(entity)
            .await()
    }

    override suspend fun getPersonaById(id: String): Persona? {
        return try {
            firestore.collection(personasCollection)
                .document(id)
                .get()
                .await()
                .toObject(CloudPersonaEntity::class.java)
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("CloudPersonaRepo", "Error fetching persona $id", e)
            Firebase.crashlytics.recordException(e)
            null
        }
    }
}