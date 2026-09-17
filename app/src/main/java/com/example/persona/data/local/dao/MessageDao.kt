package com.example.persona.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.persona.data.local.entity.MessageEntity

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE personaId = :personaId ORDER BY timestamp ASC")
    fun getMessagesByPersonaId(personaId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE personaId = :personaId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesByPersonaId(personaId: String, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String, status: String): Int

    @Query("DELETE FROM messages WHERE personaId = :personaId")
    suspend fun deleteMessagesByPersonaId(personaId: String)
}
