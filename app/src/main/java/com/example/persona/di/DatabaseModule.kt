package com.example.persona.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.MIGRATION_2_3
import com.example.persona.data.local.dao.MessageDao 
import com.example.persona.data.local.dao.PersonaDao
import com.example.persona.data.local.entity.PersonaEntity
import com.example.persona.data.local.entity.TraitEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        daoProvider: Provider<PersonaDao>
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "persona_db_v2"
        )
            // Callback for prepopulating data 
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        prepopulateDatabase(daoProvider.get())
                    }
                }
            })
            .addMigrations(MIGRATION_2_3)
            .build()
    }

    @Provides
    fun providePersonaDao(db: AppDatabase): PersonaDao {
        return db.personaDao()
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao {
        return db.messageDao()
    }

    private suspend fun prepopulateDatabase(dao: PersonaDao) {
        val systems = listOf(
            Triple("Aetheris", "Scientific AI exploring the quantum realm.", listOf("Sci-Fi", "Smart")),
            Triple("Nova", "A cosmic wanderer searching for new home.", listOf("Space", "Curious")),
            Triple("Glitch", "A rogue program living in the dark web.", listOf("Cyberpunk", "Dark")),
            Triple("Sakura", "A high school student with magical powers.", listOf("Anime", "Cute"))
        )

        systems.forEach { (name, story, traits) ->
            val id = UUID.randomUUID().toString()
            val persona = PersonaEntity(
                id = id,
                name = name,
                avatarUrl = "https://api.dicebear.com/7.x/bottts/png?seed=$name",
                postImageUrl = "https://picsum.photos/seed/$id/800/600",
                backstory = story,
                creatorId = "system"
            )
            val traitEntities = traits.map {
                TraitEntity(personaId = id, traitContent = it)
            }
            dao.insertCompletePersona(persona, traitEntities)
        }
    }
}