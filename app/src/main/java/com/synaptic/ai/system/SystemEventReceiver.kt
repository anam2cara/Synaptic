package com.synaptic.ai.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.data.model.SystemEvent

/**
 * Layer 1 (realtime murah): tangkap event sistem penting via BroadcastReceiver
 * context-registered (bukan manifest) - hidup selama proses app hidup,
 * biaya RAM saat idle mendekati nol karena OS yang membangunkan, bukan polling.
 * Setiap event ditulis 1 baris ke SystemEvent (layer 2), bukan diproses di sini.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SynapticDebug", "onReceive DIPANGGIL, action=${intent.action}")
        val event = when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", -1)
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                SystemEvent(type = "BATTERY_CHANGED", value = "level=$pct")
            }
            Intent.ACTION_POWER_CONNECTED -> SystemEvent(type = "POWER_CONNECTED", value = "")
            Intent.ACTION_POWER_DISCONNECTED -> SystemEvent(type = "POWER_DISCONNECTED", value = "")

            else -> return
        }

        Thread {
            try {
                SynapticApp.getInstance().getDatabase()?.systemEventDao()?.insert(event)
            } catch (e: Exception) {
                android.util.Log.e("SynapticDebug", "Insert SystemEvent gagal", e)
            }
        }.start()
    }

    companion object {
        fun register(context: Context) {
            android.util.Log.d("SynapticDebug", "SystemEventReceiver.register() DIPANGGIL")
            val receiver = SystemEventReceiver()

            try {
                val stickyFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                context.registerReceiver(receiver, stickyFilter)
                android.util.Log.d("SynapticDebug", "registerReceiver STICKY sukses")
            } catch (e: Exception) {
                android.util.Log.e("SynapticDebug", "registerReceiver STICKY GAGAL", e)
            }

            try {
                val powerFilter = android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                context.registerReceiver(receiver, powerFilter)
                android.util.Log.d("SynapticDebug", "registerReceiver POWER sukses")
            } catch (e: Exception) {
                android.util.Log.e("SynapticDebug", "registerReceiver POWER GAGAL", e)
            }

        }
    }
}
