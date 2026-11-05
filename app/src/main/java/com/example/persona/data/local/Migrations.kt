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

// v6 -> v7: align client seed personas with the backend's stable UUIDs while
// preserving any messages previously stored under the old random seed IDs.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SEED_PERSONAS.forEach { seed ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO personas
                    (id, name, avatarUrl, postImageUrl, backstory, creatorId, isPublic)
                VALUES (?, ?, ?, ?, ?, 'system', 1)
                """.trimIndent(),
                arrayOf(
                    seed.id,
                    seed.name,
                    seedAvatarUrl(seed.name),
                    seedPostImageUrl(seed.id),
                    seed.backstory
                )
            )

            db.execSQL(
                """
                UPDATE messages
                SET personaId = ?
                WHERE personaId IN (
                    SELECT id FROM personas
                    WHERE creatorId = 'system' AND name = ? AND id <> ?
                )
                """.trimIndent(),
                arrayOf(seed.id, seed.name, seed.id)
            )

            db.execSQL(
                "DELETE FROM personas WHERE creatorId = 'system' AND name = ? AND id <> ?",
                arrayOf(seed.name, seed.id)
            )

            db.execSQL("DELETE FROM traits WHERE personaId = ?", arrayOf(seed.id))
            seed.traits.forEach { trait ->
                db.execSQL(
                    "INSERT INTO traits (personaId, traitContent) VALUES (?, ?)",
                    arrayOf(seed.id, trait)
                )
            }
        }
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN localOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN excludeFromHistory INTEGER NOT NULL DEFAULT 0")
        // Legacy rows have no recorded consent for replay into a new prompt.
        db.execSQL("UPDATE messages SET excludeFromHistory = 1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT NOT NULL PRIMARY KEY,
                ownerId TEXT NOT NULL,
                personaId TEXT,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                sourceMessageId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                expiresAt INTEGER,
                cloudAllowed INTEGER NOT NULL DEFAULT 0,
                revision INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_memories_ownerId_personaId_deleted_updatedAt " +
                "ON memories(ownerId, personaId, deleted, updatedAt)"
        )
        installMemorySourceDeletionTrigger(db)
    }
}

internal fun installMemorySourceDeletionTrigger(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS tombstone_memories_before_message_delete
        BEFORE DELETE ON messages
        BEGIN
            UPDATE memories
            SET content = '', sourceMessageId = NULL, cloudAllowed = 0,
                deleted = 1, revision = revision + 1,
                updatedAt = MAX(updatedAt, CAST(strftime('%s', 'now') AS INTEGER) * 1000)
            WHERE sourceMessageId = OLD.id AND ownerId = OLD.ownerId AND deleted = 0;
        END
        """.trimIndent()
    )
}
