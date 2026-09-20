package com.example.persona.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.persona.data.local.AppDatabase
import com.example.persona.data.local.MIGRATION_2_3
import com.example.persona.data.local.MIGRATION_3_4
import com.example.persona.data.local.MIGRATION_4_5
import com.example.persona.data.local.MIGRATION_5_6
import com.example.persona.data.local.MIGRATION_6_7
import com.example.persona.data.local.SEED_PERSONAS
import com.example.persona.data.local.seedAvatarUrl
import com.example.persona.data.local.seedPostImageUrl
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
                    recoverInterruptedMessages(db)
                    refreshSeedPersonaBackstories(db)
                }
            })
            .addMigrations(
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7
            )
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
            val persona = PersonaEntity(
                id = seed.id,
                name = seed.name,
                avatarUrl = seedAvatarUrl(seed.name),
                postImageUrl = seedPostImageUrl(seed.id),
                backstory = seed.backstory,
                creatorId = "system"
            )
            val traitEntities = seed.traits.map {
                TraitEntity(personaId = seed.id, traitContent = it)
            }
            dao.insertCompletePersona(persona, traitEntities)
        }
    }

    private fun refreshSeedPersonaBackstories(db: SupportSQLiteDatabase) {
        SEED_PERSONAS.forEach { seed ->
            db.execSQL(
                "UPDATE personas SET backstory = ? WHERE id = ? AND creatorId = 'system'",
                arrayOf(seed.backstory, seed.id)
            )
        }
    }

    private fun recoverInterruptedMessages(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE messages
            SET status = 'STOPPED',
                content = CASE
                    WHEN content = '正在思考...' THEN '已停止生成'
                    ELSE content
                END
            WHERE status = 'GENERATING'
            """.trimIndent()
        )
    }

}
