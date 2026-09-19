package com.example.persona.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v2 → v3: no schema change; formalizes migration tracking to replace fallbackToDestructiveMigration
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {}
}

// v3 → v4: adds structured chat message status. Existing rows are normal,
// matching previous behavior where only message text implied state.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'NORMAL'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE personas ADD COLUMN isPublic INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN ownerId TEXT NOT NULL DEFAULT 'OFFLINE_USER'")
        db.execSQL("DROP INDEX IF EXISTS index_messages_personaId")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_personaId_ownerId ON messages(personaId, ownerId)")
    }
}
