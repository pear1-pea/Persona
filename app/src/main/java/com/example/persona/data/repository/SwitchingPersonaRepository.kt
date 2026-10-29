package com.example.persona.data.repository

import android.util.Log
import com.example.persona.core.auth.AuthManager
import com.example.persona.domain.model.Persona
import com.example.persona.domain.repository.PersonaRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwitchingPersonaRepository @Inject constructor(
    private val roomImpl: RoomPersonaRepository,
    private val remoteImpl: RemotePersonaRepository,
    private val authManager: AuthManager
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
        if (isCloudPreferred()) remoteImpl.getMyPersonas() else roomImpl.getMyPersonas()
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
            remoteImpl.deletePersona(id)
        } else {
            roomImpl.deletePersona(id)
        }
    }

    private suspend fun readWithFallback(
        operation: String,
        remote: suspend () -> List<Persona>,
        room: suspend () -> List<Persona>
    ): List<Persona> = authManager.withIdentity {
        if (!isCloudPreferred()) return@withIdentity room()
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
