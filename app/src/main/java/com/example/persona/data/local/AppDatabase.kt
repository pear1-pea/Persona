package com.example.persona.data.local

import com.example.persona.data.local.dao.MemoryCurationDao
import com.example.persona.data.local.entity.MemoryCandidateEntity
import com.example.persona.data.local.entity.MemoryPreferencesEntity
import com.example.persona.data.local.entity.MemorySummarySourceEntity
import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.persona.data.local.dao.MessageDao 
import com.example.persona.data.local.dao.MemoryDao
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.local.entity.MessageEntity 
import com.example.persona.data.local.entity.MemoryEntity
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.TraitEntity

@Database(
    entities = [PersonaEntity::class, TraitEntity::class, MessageEntity::class, MemoryEntity::class,
        MemoryCandidateEntity::class, MemoryPreferencesEntity::class, MemorySummarySourceEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryCurationDao(): MemoryCurationDao
    abstract fun memoryDao(): MemoryDao
}
