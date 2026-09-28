package com.synaptic.ai.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.data.model.SystemEvent

/**
 * Layer 1 (manifest-registered): khusus PACKAGE_ADDED/PACKAGE_REMOVED.
 * Dipisah dari SystemEventReceiver (context-registered) karena proses
 * yang di-freeze (state:FRZ) tidak menerima broadcast context-registered,
 * sementara manifest-registered receiver untuk PACKAGE_ADDED/PACKAGE_REMOVED
 * termasuk pengecualian yang tetap bisa membangunkan app dari kondisi beku/stopped.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SynapticDebug", "PackageInstallReceiver.onReceive DIPANGGIL, action=${intent.action}")

        val pkg = intent.data?.schemeSpecificPart ?: "unknown"
        val event = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> SystemEvent(type = "PACKAGE_ADDED", value = pkg)
            Intent.ACTION_PACKAGE_REMOVED -> SystemEvent(type = "PACKAGE_REMOVED", value = pkg)
            else -> return
        }

        Thread {
            try {
                SynapticApp.getInstance().getDatabase()?.systemEventDao()?.insert(event)
                android.util.Log.d("SynapticDebug", "PackageInstallReceiver insert sukses: ${event.type} ${event.value}")
            } catch (e: Exception) {
                android.util.Log.e("SynapticDebug", "PackageInstallReceiver insert gagal", e)
            }
        }.start()
    }
}
