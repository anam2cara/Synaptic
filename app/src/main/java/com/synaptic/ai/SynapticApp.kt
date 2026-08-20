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
        
        // Inisialisasi LlmManager dengan context
        LlmManager.getInstance().init(this)
        
        // Inisialisasi Shizuku
        ShizukuHelper.init()
    }

    fun getSecurePrefs(): SharedPreferences? = securePrefs
    fun getDatabase(): SynapticDatabase? = database

    companion object {
        private lateinit var instance: SynapticApp
        @JvmStatic
        fun getInstance(): SynapticApp = instance
    }
}
