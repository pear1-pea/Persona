package com.example.persona.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.persona.data.local.entity.MessageEntity

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE personaId = :personaId ORDER BY timestamp DESC")
    fun getMessagesByPersonaId(personaId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE personaId = :personaId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesByPersonaId(personaId: String, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?
}
