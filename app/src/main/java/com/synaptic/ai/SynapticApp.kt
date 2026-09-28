package com.synaptic.ai

import android.app.Application
import android.content.SharedPreferences
import com.synaptic.ai.data.repo.SynapticDatabase
import com.synaptic.ai.tools.ShizukuHelper
import com.synaptic.ai.llm.LlmManager

class SynapticApp : Application() {

    private var securePrefs: SharedPreferences? = null
    private var database: SynapticDatabase? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = SynapticDatabase.getInstance(this)
        pruneOldMemoriesAsync()
        com.synaptic.ai.system.SystemEventReceiver.register(this)
        com.synaptic.ai.system.NetworkObserver.register(this)
        
        // Inisialisasi LlmManager dengan context
        LlmManager.getInstance().init(this)
        
        // Inisialisasi Shizuku
        ShizukuHelper.init()
        
        // Install Global Crash Handler
        com.synaptic.ai.diagnostic.GlobalExceptionHandler.install(this)
        com.synaptic.ai.diagnostic.DiagnosticManager.addBreadcrumb("APP_START")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LlmManager.getInstance().onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LlmManager.getInstance().freeModel()
    }

    fun getSecurePrefs(): SharedPreferences? = securePrefs
    fun getDatabase(): SynapticDatabase? = database

    // Bersihkan memori lama berimportansi rendah (>30 hari tidak dipakai) agar tabel memories tidak membengkak
    private fun pruneOldMemoriesAsync() {
        Thread {
            try {
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                database?.memoryDao()?.pruneOldLowImportance(cutoff)
            } catch (_: Exception) {
                // Non-kritis: kegagalan prune tidak boleh mengganggu startup
            }
        }.start()
    }

    companion object {
        private lateinit var instance: SynapticApp
        @JvmStatic
        fun getInstance(): SynapticApp = instance
    }
}
