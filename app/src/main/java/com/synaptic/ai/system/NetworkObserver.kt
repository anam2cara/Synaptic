package com.synaptic.ai.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.data.model.SystemEvent

/**
 * Layer 1 (realtime murah) untuk network: pakai ConnectivityManager.NetworkCallback,
 * bukan BroadcastReceiver - CONNECTIVITY_ACTION sudah deprecated untuk background app
 * sejak API 24. Hidup selama proses app hidup, dipicu OS, bukan polling.
 * Sengaja hanya onAvailable/onLost (bukan onCapabilitiesChanged) untuk menghindari
 * spam event dari fluktuasi kekuatan sinyal.
 */
object NetworkObserver {

    fun register(context: Context) {
        android.util.Log.d("SynapticDebug", "NetworkObserver.register() DIPANGGIL")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                android.util.Log.d("SynapticDebug", "onAvailable DIPANGGIL")
                logEvent("NETWORK_AVAILABLE", "")
            }

            override fun onLost(network: Network) {
                android.util.Log.d("SynapticDebug", "onLost DIPANGGIL")
                logEvent("NETWORK_LOST", "")
            }
        })
    }

    private fun logEvent(type: String, value: String) {
        Thread {
            try {
                SynapticApp.getInstance().getDatabase()?.systemEventDao()?.insert(SystemEvent(type = type, value = value))
            } catch (e: Exception) {
                android.util.Log.e("SynapticDebug", "Insert SystemEvent gagal", e)
            }
        }.start()
    }
}
