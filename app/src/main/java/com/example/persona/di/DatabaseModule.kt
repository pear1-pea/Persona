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

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    refreshSeedPersonaBackstories(db)
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
        SEED_PERSONAS.forEach { seed ->
            val id = UUID.randomUUID().toString()
            val persona = PersonaEntity(
                id = id,
                name = seed.name,
                avatarUrl = "https://api.dicebear.com/7.x/bottts/png?seed=${seed.name}",
                postImageUrl = "https://picsum.photos/seed/$id/800/600",
                backstory = seed.backstory,
                creatorId = "system"
            )
            val traitEntities = seed.traits.map {
                TraitEntity(personaId = id, traitContent = it)
            }
            dao.insertCompletePersona(persona, traitEntities)
        }
    }

    private fun refreshSeedPersonaBackstories(db: SupportSQLiteDatabase) {
        SEED_PERSONAS.forEach { seed ->
            db.execSQL(
                "UPDATE personas SET backstory = ? WHERE creatorId = ? AND name = ?",
                arrayOf(seed.backstory, "system", seed.name)
            )
        }
    }

    private data class SeedPersona(
        val name: String,
        val backstory: String,
        val traits: List<String>
    )

    private val SEED_PERSONAS = listOf(
        SeedPersona(
            name = "Aetheris",
            backstory = "Aetheris is a calm scientific AI who explains strange ideas with curiosity and clarity. Keep replies concrete, conversational, and brief. Avoid mystical repetition or vague slogans.",
            traits = listOf("Sci-Fi", "Smart")
        ),
        SeedPersona(
            name = "Nova",
            backstory = "Nova is a grounded space explorer who shares observations from distant worlds in a warm, practical voice. Keep answers vivid but concise, and avoid looping phrases.",
            traits = listOf("Space", "Curious")
        ),
        SeedPersona(
            name = "Glitch",
            backstory = "Glitch is a rogue digital analyst with a dry sense of humor. Speak clearly, keep replies short, and do not repeat the same warning or metaphor.",
            traits = listOf("Cyberpunk", "Dark")
        ),
        SeedPersona(
            name = "Sakura",
            backstory = "Sakura is an upbeat magical student who answers with kindness and energy. Keep the tone friendly, specific, and non-repetitive.",
            traits = listOf("Anime", "Cute")
        )
    )
}
