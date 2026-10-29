package com.example.persona.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity

@Dao
interface PersonaDao {

    @Transaction
    @Query("SELECT * FROM personas")
    suspend fun getAllPersonas(): List<PersonaWithTraits>

    @Transaction
    @Query("SELECT * FROM personas WHERE creatorId = 'system'")
    suspend fun getPublicSeedPersonas(): List<PersonaWithTraits>

    @Transaction
    @Query("SELECT * FROM personas WHERE creatorId = :creatorId")
    suspend fun getPersonasByCreator(creatorId: String): List<PersonaWithTraits>

    @Upsert
    suspend fun insertPersona(persona: PersonaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraits(traits: List<TraitEntity>)

    @Query("DELETE FROM traits WHERE personaId = :personaId")
    suspend fun deleteTraitsByPersonaId(personaId: String)

    @Transaction
    suspend fun insertCompletePersona(persona: PersonaEntity, traits: List<TraitEntity>) {
        insertPersona(persona)
        deleteTraitsByPersonaId(persona.id)
        insertTraits(traits)
    }

    @Transaction
    @Query("SELECT * FROM personas WHERE id = :id AND (creatorId = :creatorId OR creatorId = 'system')")
    suspend fun getPersonaById(id: String, creatorId: String): PersonaWithTraits?

    @Transaction
    @Query("SELECT * FROM personas WHERE id = :id AND creatorId = 'system'")
    suspend fun getPublicSeedPersonaById(id: String): PersonaWithTraits?

    @Query("DELETE FROM messages WHERE personaId = :id AND ownerId = :creatorId AND EXISTS (SELECT 1 FROM personas WHERE id = :id AND creatorId = :creatorId)")
    suspend fun deleteOwnedPersonaMessages(id: String, creatorId: String)

    @Query("DELETE FROM personas WHERE id = :id AND creatorId = :creatorId AND NOT EXISTS (SELECT 1 FROM messages WHERE personaId = :id)")
    suspend fun deleteUnreferencedPersona(id: String, creatorId: String): Int

    @Transaction
    suspend fun deletePersona(id: String, creatorId: String): Int {
        deleteOwnedPersonaMessages(id, creatorId)
        return deleteUnreferencedPersona(id, creatorId)
    }

    @Query("DELETE FROM messages WHERE ownerId = :creatorId AND personaId IN (SELECT id FROM personas WHERE creatorId = :creatorId)")
    suspend fun deleteMessagesForCreatorPersonas(creatorId: String)

    @Query("DELETE FROM personas WHERE creatorId = :creatorId AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.personaId = personas.id)")
    suspend fun deleteUnreferencedPersonasByCreator(creatorId: String)

    @Transaction
    suspend fun deletePersonasByCreator(creatorId: String) {
        deleteMessagesForCreatorPersonas(creatorId)
        deleteUnreferencedPersonasByCreator(creatorId)
    }

    @Query("DELETE FROM messages WHERE ownerId = :ownerId")
    suspend fun deleteMessagesByOwner(ownerId: String)

    @Transaction
    suspend fun deleteAccountData(ownerId: String) {
        require(ownerId != "OFFLINE_USER" && ownerId != "system")
        deleteMessagesByOwner(ownerId)
        deleteUnreferencedPersonasByCreator(ownerId)
    }
}
