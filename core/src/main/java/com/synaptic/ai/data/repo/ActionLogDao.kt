package com.synaptic.ai.data.repo

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.synaptic.ai.data.model.ActionLog

@Dao
interface ActionLogDao {
    @Insert
    fun insert(log: ActionLog): Long

    @Query("SELECT * FROM action_logs ORDER BY executedAt DESC LIMIT 100")
    fun getRecentLogs(): LiveData<List<ActionLog>>

    @Query("SELECT * FROM action_logs ORDER BY executedAt DESC LIMIT 100")
    fun getRecentLogsSync(): List<ActionLog>

    @Query("DELETE FROM action_logs WHERE executedAt < :cutoffTime")
    fun deleteBefore(cutoffTime: Long)

    @Query("DELETE FROM action_logs")
    fun clearAll()
}
