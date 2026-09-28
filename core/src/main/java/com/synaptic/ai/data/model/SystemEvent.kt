package com.synaptic.ai.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SystemEvent: catatan realtime dari BroadcastReceiver (battery, power, network, package).
 * Ini yang bikin pertanyaan temporal ("vs 10 menit lalu") bisa dijawab -
 * sebelumnya tidak ada histori tersimpan sama sekali.
 */
@Entity(tableName = "system_events")
data class SystemEvent(
    val type: String,   // "BATTERY_CHANGED" | "POWER_CONNECTED" | "POWER_DISCONNECTED" | "NETWORK_CHANGED" | "PACKAGE_ADDED" | "PACKAGE_REMOVED"
    val value: String,  // payload bebas, misal "level=54" atau nama package
    val timestamp: Long = System.currentTimeMillis()
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
}
