package com.synaptic.ai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Log semua tool call / eksekusi command yang dijalankan LLM */
@Entity(tableName = "action_logs")
class ActionLog(
    var command: String,
    var result: String,
    var wasConfirmed: Boolean, // apakah user approve sebelum eksekusi
    var sessionId: String
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    var executedAt: Long = System.currentTimeMillis()
}
