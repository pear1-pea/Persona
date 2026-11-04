package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.SessionTokenStore
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import com.example.persona.domain.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwitchingPersonaRepository @Inject constructor(
    private val roomImpl: RoomPersonaRepository,
    private val remoteImpl: RemotePersonaRepository,
    private val authManager: AuthManager,
    private val memoryRepository: MemoryRepository,
    private val sessionTokenStore: SessionTokenStore
) : PersonaRepository {

    /*
     * Authenticated sessions use the backend as the authority. Offline sessions use
     * Room as an independent local source. Offline personas are not uploaded
     * automatically, and the two sources are never merged by id.
     */

    private suspend fun isCloudPreferred(): Boolean = authManager.requestIdentity().token != null

    override suspend fun getPersonas(): List<Persona> = readWithFallback(
        operation = "get public personas",
        remote = { remoteImpl.getPersonas() },
        room = { roomImpl.getPublicSeedPersonas() }
    )

    override suspend fun getMyPersonas(): List<Persona> = authManager.withIdentity {
        if (isCloudPreferred()) {
            val personas = remoteImpl.getMyPersonas()
            val ownerId = authManager.requestIdentity().ownerId
            val remainingIds = personas.map { it.id }.toSet()
            for (id in sessionTokenStore.pendingPersonaDeletions(ownerId)) {
                if (id !in remainingIds) completePersonaCleanup(ownerId, id)
            }
            personas
        } else roomImpl.getMyPersonas()
    }

    override suspend fun addPersona(name: String, traits: List<String>, backstory: String, isPublic: Boolean) = authManager.withIdentity {
        if (isCloudPreferred()) {
            remoteImpl.addPersona(name, traits, backstory, isPublic)
        } else {
            roomImpl.addPersona(name, traits, backstory, isPublic)
        }
    }

    override suspend fun updatePersona(
        id: String,
        name: String,
        traits: List<String>,
        backstory: String,
        isPublic: Boolean
    ) = authManager.withIdentity {
        if (isCloudPreferred()) {
            remoteImpl.updatePersona(id, name, traits, backstory, isPublic)
        } else {
            roomImpl.updatePersona(id, name, traits, backstory, isPublic)
        }
    }

    override suspend fun getPersonaById(id: String): Persona? = authManager.withIdentity {
        if (!isCloudPreferred()) return@withIdentity roomImpl.getPersonaById(id)
        try {
            remoteImpl.getPersonaById(id) ?: roomImpl.getPublicSeedPersonaById(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            authManager.requestIdentity()
            Log.w(TAG, "Backend persona detail unavailable; checking public Room seed", error)
            roomImpl.getPublicSeedPersonaById(id)
        }
    }

    override suspend fun deletePersona(id: String) = authManager.withIdentity {
        if (isCloudPreferred()) {
            val ownerId = authManager.requestIdentity().ownerId
            if (id !in sessionTokenStore.pendingPersonaDeletions(ownerId)) {
                check(remoteImpl.getPersonaById(id)?.creatorId == ownerId) {
                    "Persona is unavailable or is owned by another account"
                }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                authManager.requestIdentity()
                sessionTokenStore.markPersonaDeletionPending(ownerId, id)
            }
            try {
                remoteImpl.deletePersona(id)
            } catch (error: HttpException) {
                if (error.code() != 404) throw error
            }
            completePersonaCleanup(ownerId, id)
        } else {
            roomImpl.deletePersona(id)
            memoryRepository.deleteForPersona(id)
        }
    }

    private suspend fun completePersonaCleanup(ownerId: String, id: String) = withContext(NonCancellable + Dispatchers.IO) {
        authManager.requestIdentity()
        memoryRepository.deleteForPersona(id)
        sessionTokenStore.completePersonaDeletion(ownerId, id)
    }

    private suspend fun readWithFallback(
        operation: String,
        remote: suspend () -> List<Persona>,
        room: suspend () -> List<Persona>
    ): List<Persona> = authManager.withIdentity {
        if (!isCloudPreferred()) return@withIdentity roomImpl.getPersonas().filter { it.isPublic }
        try {
            remote()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            authManager.requestIdentity()
            Log.w(TAG, "Backend persona source unavailable; using Room for $operation", error)
            room()
        }
    }

    private companion object {
        const val TAG = "SwitchingPersonaRepo"
    }
}
