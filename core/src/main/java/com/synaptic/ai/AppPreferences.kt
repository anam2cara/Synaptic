package com.synaptic.ai

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_DEBUG_LOGS = "debug_logs"
        const val KEY_CONFIRM_BEFORE_EXEC = "confirm_before_exec"
        const val KEY_USE_GPU_BACKEND = "use_gpu_backend"
        const val KEY_PGVECTOR_URL = "pgvector_url"
        const val KEY_PGVECTOR_API_KEY = "pgvector_api_key"
        const val KEY_N8N_WEBHOOK_URL = "n8n_webhook_url"
        const val KEY_N8N_API_KEY = "n8n_api_key"
        // Default model dipindahkan ke folder app agar selalu bisa dibaca Synaptic
        const val DEFAULT_MODEL_PATH = "/storage/emulated/0/Android/data/com.synaptic.ai/files/models/Qwen3-0.6B-Q4_K_M.gguf"
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

    var useGpuBackend: Boolean
        get() = prefs?.getBoolean(KEY_USE_GPU_BACKEND, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_USE_GPU_BACKEND, value)?.apply()
        }

    var pgVectorUrl: String
        get() = sanitizePath(prefs?.getString(KEY_PGVECTOR_URL, "") ?: "")
        set(value) {
            prefs?.edit()?.putString(KEY_PGVECTOR_URL, sanitizePath(value))?.apply()
        }

    var pgVectorApiKey: String
        get() = sanitizePath(prefs?.getString(KEY_PGVECTOR_API_KEY, "") ?: "")
        set(value) {
            prefs?.edit()?.putString(KEY_PGVECTOR_API_KEY, sanitizePath(value))?.apply()
        }

    var n8nWebhookUrl: String
        get() = sanitizePath(prefs?.getString(KEY_N8N_WEBHOOK_URL, "") ?: "")
        set(value) {
            prefs?.edit()?.putString(KEY_N8N_WEBHOOK_URL, sanitizePath(value))?.apply()
        }

    var n8nApiKey: String
        get() = sanitizePath(prefs?.getString(KEY_N8N_API_KEY, "") ?: "")
        set(value) {
            prefs?.edit()?.putString(KEY_N8N_API_KEY, sanitizePath(value))?.apply()
        }
}





