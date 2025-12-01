package com.example.persona.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.persona.data.local.dao.MessageDao 
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.local.entity.MessageEntity 
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.TraitEntity

@Database(
    entities = [PersonaEntity::class, TraitEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun messageDao(): MessageDao
}