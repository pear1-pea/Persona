package com.example.persona.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.PersonaWithTraits
import com.example.persona.data.local.entity.TraitEntity

@Dao
interface PersonaDao {

    @Transaction
    @Query("SELECT * FROM personas")
    suspend fun getAllPersonas(): List<PersonaWithTraits>

    @Transaction
    @Query("SELECT * FROM personas WHERE creatorId = :creatorId")
    suspend fun getPersonasByCreator(creatorId: String): List<PersonaWithTraits>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersona(persona: PersonaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraits(traits: List<TraitEntity>)

    @Transaction
    suspend fun insertCompletePersona(persona: PersonaEntity, traits: List<TraitEntity>) {
        insertPersona(persona)
        insertTraits(traits)
    }

    @Transaction
    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getPersonaById(id: String): PersonaWithTraits?
}