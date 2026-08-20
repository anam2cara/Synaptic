package com.synaptic.ai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Memory: konteks yang diingat LLM antar sesi.
 * Contoh: "User biasanya minta cek RAM", "App berat = com.berat.app"
 */
@Entity(tableName = "memories")
class Memory(
    var key: String,       // kategori singkat, misal "device_habit"
    var value: String,     // isi memory
    var importance: Float  // 0.0 - 1.0, makin tinggi makin jarang dihapus
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    var createdAt: Long = System.currentTimeMillis()
    var lastUsedAt: Long = System.currentTimeMillis()
    var useCount: Int = 0
}
