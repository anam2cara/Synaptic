package com.synaptic.ai.data.repo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.synaptic.ai.data.model.Memory

@Dao
interface MemoryDao {
    @Insert
    fun insert(memory: Memory): Long

    @Update
    fun update(memory: Memory)

    @Query("SELECT * FROM memories ORDER BY importance DESC, lastUsedAt DESC LIMIT :limit")
    fun getTopMemories(limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE key = :key LIMIT 1")
    fun getByKey(key: String): Memory?

    @Query("UPDATE memories SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id")
    fun incrementUse(id: Long, now: Long)

    @Query("DELETE FROM memories WHERE importance < 0.3 AND lastUsedAt < :cutoffTime")
    fun pruneOldLowImportance(cutoffTime: Long)

    @Query("DELETE FROM memories")
    fun clearAll()
}
