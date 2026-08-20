package com.synaptic.ai.data.repo

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.synaptic.ai.data.model.ChatMessage

@Dao
interface ChatDao {
    @Insert
    fun insert(message: ChatMessage): Long

    @Update
    fun update(message: ChatMessage)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesLive(sessionId: String): LiveData<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesSync(sessionId: String): List<ChatMessage>

    @Query("SELECT DISTINCT sessionId FROM messages ORDER BY sessionId DESC")
    fun getAllSessionIds(): List<String>

    /** Mengambil pesan asisten pertama tiap sesi untuk ringkasan di sidebar */
    @Query("SELECT * FROM messages GROUP BY sessionId ORDER BY timestamp DESC")
    fun getSessionSummariesSync(): List<ChatMessage>

    @Query("UPDATE messages SET sessionTitle = :newTitle WHERE sessionId = :sessionId")
    fun renameSession(sessionId: String, newTitle: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    fun deleteSession(sessionId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND timestamp >= :afterTimestamp")
    fun deleteMessagesAfter(sessionId: String, afterTimestamp: Long)

    @Query("DELETE FROM messages")
    fun clearAll()
}
