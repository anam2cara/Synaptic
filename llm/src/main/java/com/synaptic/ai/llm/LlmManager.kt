package com.synaptic.ai.llm

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.data.model.ChatMessage
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LlmManager private constructor() {

    private var context: Context? = null

    private val jni = LlamaJNI()
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val MAX_HISTORY_MESSAGES = 12 
    private val MAX_HISTORY_MESSAGE_CHARS = 1000
    private val MAX_HISTORY_TOTAL_CHARS = 4000 
    private val MAX_DEVICE_CONTEXT_CHARS = 1500
    private val MAX_MEMORIES_CHARS = 500

    private fun writeDiagnostic(tag: String, message: String) {
        try {
            val dir = context?.getExternalFilesDir(null)
            val logFile = File(dir, "diagnostic.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("id")).format(java.util.Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "writeDiagnostic gagal: ${e.message}")
        }
    }

    private fun copyModelToAppStorage(source: File, appFilesDir: File): File {
        // Jika file sumber sudah ada di folder internal, jangan copy lagi
        if (source.parentFile?.absolutePath == appFilesDir.absolutePath) return source
        
        if (!appFilesDir.exists()) appFilesDir.mkdirs()

        val target = File(appFilesDir, source.name)
        if (target.exists() && target.length() == source.length()) return target

        // Bersihkan model lain agar storage tidak bengkak
        appFilesDir.listFiles()?.forEach { 
            if (it.name.endsWith(".gguf") && it.name != source.name) {
                it.delete()
            }
        }

        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyalin model: ${e.message}")
            // Jika gagal copy karena permission, biarkan loadModel yang menangani error-nya
        }
        return target
    }

    private fun resolveModelPath(rawPath: String, appFilesDir: File): File {
        val fileName = File(rawPath).name
        val internalFile = File(appFilesDir, fileName)
        
        // Android 13+: Hanya izinkan file yang sudah ada di folder aplikasi
        if (internalFile.exists()) return internalFile

        val candidate = File(rawPath)
        // Jika path manual ternyata valid dan bisa dibaca (misal di folder app lain)
        if (candidate.exists() && candidate.canRead()) return candidate

        return internalFile
    }

    var isLoading: Boolean = false
        private set

    data class ModelInfo(val name: String, val useGpu: Boolean, val path: String)
    var loadedModelInfo: ModelInfo? = null
        private set

    private val pendingLoadCallbacks = java.util.Collections.synchronizedList(mutableListOf<LoadCallback>())

    interface LoadCallback {
        fun onSuccess()
        fun onError(msg: String)
    }

    interface GenerateCallback {
        fun onResult(result: String)
        fun onToken(token: String)
        fun onComplete(fullResponse: String)
        fun onError(msg: String?)
    }

    fun init(ctx: Context) {
        this.context = ctx.applicationContext
    }

    fun onTrimMemory(level: Int) {
        val shouldFree = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> true
            else -> false
        }
        if (shouldFree && isLoaded() && !isLoading) {
            Log.w(TAG, "Low memory (level $level). Freeing RAM.")
            freeModel()
        }
    }

    private fun getRecommendedCtxSize(ctx: Context): Int {
        return try {
            val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem / (1024 * 1024 * 1024.0)
            when {
                totalRamGb >= 10.0 -> 3072
                totalRamGb >= 7.0 -> 2048
                totalRamGb >= 5.0 -> 1536
                else -> 1024
            }
        } catch (e: Exception) { 1792 }
    }

    fun isLoaded(): Boolean = jni.isLoaded()
    fun clearCache() { jni.clearCache() }
    fun stopGeneration() { jni.stopGeneration() }

    fun freeModel() {
        jni.freeModel()
        loadedModelInfo = null
        writeDiagnostic("FREE_MODEL", "Model dibebaskan")
    }

    fun loadModel(cb: LoadCallback) {
        val ctx = context ?: run { cb.onError("LlmManager belum diinisialisasi"); return }
        val targetPath = AppPreferences(ctx).modelPath
        val useGpu = AppPreferences(ctx).useGpuBackend
        
        if (jni.isLoaded() && loadedModelInfo?.path == targetPath && loadedModelInfo?.useGpu == useGpu) {
            cb.onSuccess()
            return 
        }

        synchronized(this) {
            pendingLoadCallbacks.add(cb)
            if (isLoading) return
            isLoading = true
            loadedModelInfo = null 
        }

        executor.execute {
            try {
                val appFilesDir = ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models").also { it.mkdirs() }
                val modelFile = resolveModelPath(targetPath, appFilesDir)

                if (!modelFile.exists()) {
                    notifyLoadError("Model tidak ditemukan di storage internal. Silakan 'Import Model GGUF' kembali melalui menu utama.")
                    return@execute
                }

                val loadStartMs = System.currentTimeMillis()
                val nCtx = getRecommendedCtxSize(ctx)
                
                Log.i(TAG, "Native load: ${modelFile.absolutePath} (GPU=$useGpu)")
                var ok = jni.loadModel(modelFile.absolutePath, useGpu, nCtx)

                // AUTO-FALLBACK: Jika GPU gagal (Vulkan Error), matikan permanen (Permanent Fix)
                if (!ok && useGpu) {
                    Log.e(TAG, "Vulkan Incompatibility Detected. Switching to CPU permanently for this device.")
                    AppPreferences(ctx).useGpuBackend = false // MATIKAN di Settings otomatis
                    ok = jni.loadModel(modelFile.absolutePath, false, nCtx)
                }

                if (ok) {
                    val finalUseGpu = if (!ok && useGpu) false else useGpu
                    loadedModelInfo = ModelInfo(modelFile.name, AppPreferences(ctx).useGpuBackend, modelFile.absolutePath)
                    notifyLoadSuccess()
                } else {
                    jni.freeModel()
                    notifyLoadError("Engine native menolak model. Pastikan RAM cukup dan file GGUF valid.")
                }
            } catch (e: Exception) {
                notifyLoadError(e.message ?: "Error load model")
            } finally {
                synchronized(this) { isLoading = false }
            }
        }
    }

    private fun notifyLoadSuccess() {
        val callbacks: List<LoadCallback>
        synchronized(this) { callbacks = pendingLoadCallbacks.toList(); pendingLoadCallbacks.clear() }
        callbacks.forEach { it.onSuccess() }
    }

    private fun notifyLoadError(msg: String) {
        val callbacks: List<LoadCallback>
        synchronized(this) { callbacks = pendingLoadCallbacks.toList(); pendingLoadCallbacks.clear() }
        callbacks.forEach { it.onError(msg) }
    }

    fun generate(userMessage: String, history: List<ChatMessage>?, memories: String? = null, deviceContext: String? = null, cb: GenerateCallback) {
        if (!jni.isLoaded()) { cb.onError("Model belum dimuat"); return }
        executor.execute {
            try {
                val genStartMs = System.currentTimeMillis()
                val prompt = buildPrompt(userMessage, history, memories, deviceContext)
                val grammar = SystemPromptBuilder.getToolGrammar()
                
                jni.generateStream(prompt, grammar, 512, object : LlamaJNI.StreamCallback {
                    override fun onToken(token: String) { cb.onToken(token) }
                    override fun onComplete() { cb.onComplete("") }
                    override fun onError(message: String) {
                        jni.freeModel()
                        loadedModelInfo = null
                        if (message.contains("vk", ignoreCase = true)) {
                            context?.let { AppPreferences(it).useGpuBackend = false }
                            cb.onError("GPU ERROR: $message. Beralih ke CPU...")
                        } else cb.onError(message)
                    }
                })
            } catch (e: Exception) { cb.onError(e.message) }
        }
    }

    private fun buildPrompt(userMessage: String, history: List<ChatMessage>?, memories: String?, deviceContext: String?): String {
        return buildString {
            append("<|im_start|>system\n${SystemPromptBuilder.buildSystemPrompt()}<|im_end|>\n")
            val trimmedHistory = ContextEngine.compressHistory(history ?: emptyList()).takeLast(MAX_HISTORY_MESSAGES)
            trimmedHistory.forEach { msg ->
                val role = when(msg.role) { "user"->"user"; "assistant"->"assistant"; "tool_result"->"system"; "tool_call"->"assistant"; else->"user" }
                append("<|im_start|>$role\n${msg.content.take(1000)}<|im_end|>\n")
            }
            if (memories != null || deviceContext != null) {
                append("<|im_start|>system\n")
                if (memories != null) append("Memori: $memories\n")
                if (deviceContext != null) append("Konteks: $deviceContext\n")
                append("<|im_end|>\n")
            }
            if (userMessage.isNotEmpty()) append("<|im_start|>user\n$userMessage<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        private const val TAG = "LlmManager"
        @Volatile private var instance: LlmManager? = null
        fun getInstance(): LlmManager = instance ?: synchronized(this) { instance ?: LlmManager().also { instance = it } }
    }
}
