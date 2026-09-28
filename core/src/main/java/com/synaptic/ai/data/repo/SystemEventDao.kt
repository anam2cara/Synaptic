package com.synaptic.ai.data.repo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.synaptic.ai.data.model.SystemEvent

@Dao
interface SystemEventDao {
    @Insert
    fun insert(event: SystemEvent): Long

    @Query("SELECT * FROM system_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): List<SystemEvent>

    @Query("SELECT * FROM system_events WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentByType(type: String, limit: Int): List<SystemEvent>

    @Query("DELETE FROM system_events WHERE timestamp < :cutoffTime")
    fun pruneOlderThan(cutoffTime: Long)
}
