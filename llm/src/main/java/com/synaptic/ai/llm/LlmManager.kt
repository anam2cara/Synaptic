package com.synaptic.ai.llm

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.data.model.ChatMessage
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LlmManager private constructor() {

    private var context: Context? = null
    private var jni: NativeBridge = LlamaBridge()
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val MAX_HISTORY_MESSAGES = 12 
    
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
            val targetPath = AppPreferences(ctx).modelPath
            val isHeavy = targetPath.contains("4B", ignoreCase = true) || targetPath.contains("3B", ignoreCase = true)

            when {
                totalRamGb >= 10.0 -> if (isHeavy) 2048 else 3072
                totalRamGb >= 7.0 -> if (isHeavy) 1024 else 2048
                totalRamGb >= 3.0 -> 512
                else -> 256
            }
        } catch (e: Exception) { 1024 }
    }

    fun isLoaded(): Boolean = jni.isLoaded()
    fun clearCache() { jni.clearCache() }
    fun stopGeneration() { jni.stopGeneration() }

    fun freeModel() {
        jni.freeModel()
        loadedModelInfo = null
    }

    /**
     * Solusi Ampuh: Hybrid Storage Logic
     * 1. Coba baca langsung (Zero-Copy) jika punya izin.
     * 2. Jika izin ditolak (EACCES), otomatis salin ke internal personal folder.
     * 3. Selalu hapus model internal lama agar total storage tetap hemat (~500MB).
     */
    fun loadModel(cb: LoadCallback) {
        val ctx = context ?: run { cb.onError("LlmManager belum diinisialisasi"); return }
        val targetPath = AppPreferences(ctx).modelPath
        val useGpu = AppPreferences(ctx).useGpuBackend
        
        if (isLoaded() && loadedModelInfo?.path == targetPath && loadedModelInfo?.useGpu == useGpu) {
            cb.onSuccess()
            return 
        }

        // AGGRESSIVE RAM CLEANUP: Sebelum muat model 4B, bebaskan RAM sistem sebanyak mungkin
        if (targetPath.contains("4B", ignoreCase = true)) {
            Log.i(TAG, "Heavy model (4B) detected. Clearing cache and GC...")
            jni.freeModel()
            System.gc()
            Runtime.getRuntime().gc()
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
                var sourceFile = File(targetPath)
                var finalModelFile = sourceFile

                // JIKA tidak bisa baca langsung dari lokasi asal (EACCES)
                if (!sourceFile.exists() || !sourceFile.canRead()) {
                    Log.w(TAG, "Akses langsung ditolak. Mencoba mencari/menyalin ke storage internal...")
                    
                    val internalCopy = File(appFilesDir, sourceFile.name)
                    
                    if (internalCopy.exists() && internalCopy.length() == sourceFile.length()) {
                        finalModelFile = internalCopy
                    } else if (sourceFile.exists()) {
                        // OTOMATIS COPY: Hanya jika file sumber ada tapi tidak bisa dibaca native
                        // Ini terjadi pada Android 11+ tanpa MANAGE_EXTERNAL_STORAGE
                        Log.i(TAG, "Menyalin model ke internal untuk mem-bypass batasan izin Android...")
                        
                        // Bersihkan model internal lama agar storage tidak bengkak
                        appFilesDir.listFiles()?.forEach { if (it.name.endsWith(".gguf")) it.delete() }
                        
                        sourceFile.inputStream().use { input ->
                            internalCopy.outputStream().use { output -> input.copyTo(output) }
                        }
                        finalModelFile = internalCopy
                    }
                }

                if (!finalModelFile.exists() || !finalModelFile.canRead()) {
                    notifyLoadError("MODEL TIDAK DAPAT DIAKSES: Silakan gunakan tombol 'Import Model GGUF' di sidebar dan pilih file model kembali.")
                    return@execute
                }

                // Jika target adalah direktori model MLC, gunakan MLC bridge (native MLC belum ada — stub dipakai)
                if (finalModelFile.isDirectory) {
                    val configFile = File(finalModelFile, "mlc-chat-config.json")
                    if (configFile.exists()) {
                        Log.i(TAG, "Detected MLC model directory, switching to MLC engine bridge")
                        jni = MlcBridge()
                    }
                }

                val nCtx = getRecommendedCtxSize(ctx)
                Log.i(TAG, "Native load: ${finalModelFile.absolutePath} (GPU=$useGpu)")
                
                // PERBAIKAN PERMANEN GPU: Jika GPU Gagal, JANGAN BERI PESAN ERROR MERAH, langsung CPU
                var ok = jni.loadModel(finalModelFile.absolutePath, useGpu, nCtx)
                
                if (!ok && useGpu) {
                    Log.e(TAG, "Vulkan Pipeline Error. Switching to CPU mode PERMANENTLY for this device.")
                    AppPreferences(ctx).useGpuBackend = false // MATIKAN SETTING GPU
                    ok = jni.loadModel(finalModelFile.absolutePath, false, nCtx)
                }

                if (ok) {
                    loadedModelInfo = ModelInfo(finalModelFile.name, AppPreferences(ctx).useGpuBackend, targetPath)
                    notifyLoadSuccess()
                } else {
                    notifyLoadError("ENGINE GAGAL: RAM tidak cukup atau file model rusak.")
                }
            } catch (e: Exception) {
                notifyLoadError("ERROR: ${e.message}")
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
                val prompt = buildPrompt(userMessage, history, memories, deviceContext)
                val grammar = SystemPromptBuilder.getToolGrammar()
                
                jni.generateStream(prompt, grammar, 512, object : LlamaJNI.StreamCallback {
                    override fun onToken(token: String) { cb.onToken(token) }
                    override fun onComplete() { cb.onComplete("") }
                    override fun onError(message: String) {
                        jni.freeModel()
                        loadedModelInfo = null
                        // JIKA ERROR GPU SAAT GENERATE: Matikan GPU permanen (Auto-Recovery)
                        if (message.contains("vk", ignoreCase = true) || message.contains("pipeline", ignoreCase = true) || message.contains("Device", ignoreCase = true)) {
                            context?.let { AppPreferences(it).useGpuBackend = false }
                            cb.onError("GPU LIMIT: Model 4B terlalu berat untuk resource grafis Anda. Beralih ke CPU (Stabil)...")
                        } else {
                            cb.onError("ENGINE ERROR: $message. HP Anda mungkin kehabisan RAM untuk model 4B ini.")
                        }
                    }
                })
            } catch (e: Exception) { cb.onError("Generate Error: ${e.message}") }
        }
    }

    private fun buildPrompt(userMessage: String, history: List<ChatMessage>?, memories: String?, deviceContext: String?): String {
        return buildString {
            append("<|im_start|>system\n${SystemPromptBuilder.buildSystemPrompt()}<|im_end|>\n")
            val trimmedHistory = ContextEngine.compressHistory(history ?: emptyList()).takeLast(MAX_HISTORY_MESSAGES)
            trimmedHistory.forEach { msg ->
                val role = when(msg.role) { "user"->"user"; "assistant"->"assistant"; "tool_result"->"system"; "tool_call"->"assistant"; else->"user" }
                append("<|im_start|>$role\n${msg.content}<|im_end|>\n")
            }
            if (memories != null || deviceContext != null) {
                append("<|im_start|>system\n")
                if (memories != null) append("Context: $memories\n")
                if (deviceContext != null) append("Device Diagnostics: $deviceContext\n")
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
