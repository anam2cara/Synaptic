package com.synaptic.ai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class ChatMessage(
    val sessionId: String,
    val role: String, // "user" or "assistant"
    var content: String,
    val timestamp: Long = System.currentTimeMillis(),
    var sessionTitle: String? = null
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
}
