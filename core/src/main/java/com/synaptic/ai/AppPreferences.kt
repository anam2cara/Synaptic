package com.synaptic.ai

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_DEBUG_LOGS = "debug_logs"
        const val KEY_CONFIRM_BEFORE_EXEC = "confirm_before_exec"
        // Default model dipindahkan ke folder app agar selalu bisa dibaca Synaptic
        const val DEFAULT_MODEL_PATH = "/storage/emulated/0/Android/data/com.synaptic.ai/files/models/Qwen3-1.7B-Q4_K_M.gguf"
    }

    private val prefs: SharedPreferences? = context.getSharedPreferences("synaptic_prefs", Context.MODE_PRIVATE)

    var modelPath: String
        get() = sanitizePath(prefs?.getString(KEY_MODEL_PATH, DEFAULT_MODEL_PATH) ?: DEFAULT_MODEL_PATH)
        set(path) {
            prefs?.edit()?.putString(KEY_MODEL_PATH, sanitizePath(path))?.apply()
        }

    private fun sanitizePath(raw: String): String =
        raw.replace("\r", "").replace("\n", "")
            .removeSurrounding("\"").removeSurrounding("'")
            .trim()

    var isConfirmBeforeExec: Boolean
        get() = prefs?.getBoolean(KEY_CONFIRM_BEFORE_EXEC, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_CONFIRM_BEFORE_EXEC, value)?.apply()
        }

    var debugLogs: String
        get() = prefs?.getString(KEY_DEBUG_LOGS, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_DEBUG_LOGS, value)?.apply()
        }
}





