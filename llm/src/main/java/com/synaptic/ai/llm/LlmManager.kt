package com.synaptic.ai.llm

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
    private val MAX_HISTORY_MESSAGE_CHARS = 1500
    private val MAX_HISTORY_TOTAL_CHARS = 7000
    private val MAX_DEVICE_CONTEXT_CHARS = 3000
    private val MAX_MEMORIES_CHARS = 1800

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
        if (source.parentFile?.absolutePath == appFilesDir.absolutePath) return source
        if (!appFilesDir.exists()) appFilesDir.mkdirs()

        val target = File(appFilesDir, source.name)
        if (target.exists() && target.length() == source.length()) return target

        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun migrateLegacyModelPath(rawPath: String): String {
        val trimmed = rawPath.trim().removeSurrounding("\"").removeSurrounding("'")
        val legacyPrefix = "/storage/emulated/0/Documents/Berkas_lain/LLM model/"
        if (trimmed.startsWith(legacyPrefix, ignoreCase = true)) {
            val fileName = File(trimmed).name
            return "/storage/emulated/0/Android/data/com.synaptic.ai/files/models/$fileName"
        }
        return trimmed
    }

    private fun resolveModelPath(rawPath: String, appFilesDir: File): File {
        val trimmed = migrateLegacyModelPath(rawPath)
        val androidPath = trimmed.replaceFirst("^/?Internal storage/".toRegex(), "/storage/emulated/0/")
        val candidate = when {
            androidPath.isEmpty() -> appFilesDir
            androidPath.startsWith("/") -> File(androidPath)
            androidPath.matches(Regex("^[A-Za-z]:\\\\.*")) -> File(androidPath)
            else -> File(appFilesDir, androidPath)
        }
        if (candidate.exists()) return candidate

        candidate.parentFile?.takeIf { it.exists() }?.let { parent ->
            parent.listFiles()?.firstOrNull { it.isFile && it.name.equals(candidate.name, ignoreCase = true) }?.let { return it }
            val prefix = candidate.name.substringBeforeLast('-').lowercase()
            parent.listFiles()?.firstOrNull {
                it.isFile && it.name.lowercase().endsWith(".gguf") && it.name.lowercase().startsWith(prefix)
            }?.let { return it }
            parent.listFiles()?.firstOrNull { it.isFile && it.name.lowercase().endsWith(".gguf") }?.let { return it }
            val wanted = candidate.name.lowercase().replace(" ", "")
            parent.listFiles()?.firstOrNull {
                it.isFile && it.name.lowercase().endsWith(".gguf") &&
                    it.name.lowercase().replace(" ", "").contains(wanted.take(12))
            }?.let { return it }
        }

        context?.getExternalFilesDir("models")?.takeIf { it.exists() }?.listFiles()?.firstOrNull { it.isFile && it.name.lowercase().endsWith(".gguf") }?.let { return it }

        return candidate
    }

    var isLoading: Boolean = false
        private set

    // Antrean callback untuk load yang SEDANG berjalan. Ini guard reentrancy:
    // mencegah loadModel() yang dipanggil ulang saat load sebelumnya belum selesai
    // memicu clearCache()+reload native lagi (root cause LOAD_MODEL/CLEAR_CACHE berulang).
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

    fun isLoaded(): Boolean = jni.isLoaded()

    fun clearCache() {
        jni.clearCache()
    }

    fun stopGeneration() {
        jni.stopGeneration()
    }

    fun freeModel() {
        jni.freeModel()
        writeDiagnostic("FREE_MODEL", "Model dibebaskan dari RAM")
    }

    fun loadModel(cb: LoadCallback) {
        if (jni.isLoaded()) { cb.onSuccess(); return }

        val shouldStartLoad: Boolean
        synchronized(this) {
            pendingLoadCallbacks.add(cb)
            shouldStartLoad = !isLoading
            if (shouldStartLoad) isLoading = true
        }
        if (!shouldStartLoad) {
            // Sudah ada load yang berjalan di executor. cb sudah diantrekan di atas
            // dan akan dipanggil saat load itu selesai. TIDAK mulai load baru --
            // ini yang mencegah reload & clearCache berulang.
            return
        }

        val ctx = context ?: run {
            synchronized(this) { isLoading = false }
            notifyLoadError("LlmManager belum diinisialisasi")
            return
        }
        executor.execute {
            try {
                writeDiagnostic("LOAD_MODEL", "loadModel() dipanggil")
                // jni.clearCache() SENGAJA DIHAPUS: native loadModel() sudah memanggil
                // freeStateLocked() yang mereset model+context+KV cache duluan,
                // jadi clearCache() eksplisit di sini adalah kerja ganda yang sia-sia.

                val path = AppPreferences(ctx).modelPath
                val migratedPath = migrateLegacyModelPath(path)
                
                if (path.isEmpty() || path == "model.gguf" || migratedPath.startsWith("/storage/emulated/0/Android/data/com.synaptic.ai/files/models/")) {
                    try {
                        val assetManager = ctx.assets
                        val assetsFiles = assetManager.list("") ?: emptyArray()
                        val modelInAssets = assetsFiles.find { it.endsWith(".gguf") }
                        
                        if (modelInAssets != null) {
                            val cacheFile = File(ctx.filesDir, modelInAssets)
                            if (!cacheFile.exists()) {
                                assetManager.open(modelInAssets).use { input ->
                                    cacheFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal memproses model dari assets", e)
                    }
                }

                val appFilesDir = ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models").also { it.mkdirs() }
                val normalizedPath = migratedPath
                val sourceModelFile = resolveModelPath(normalizedPath, appFilesDir)
                val modelFile = copyModelToAppStorage(sourceModelFile, appFilesDir)
                AppPreferences(ctx).modelPath = modelFile.absolutePath

                Log.i(TAG, "Mencoba memuat model dari: ${modelFile.absolutePath}")
                if (!modelFile.exists() || modelFile.isDirectory()) {
                    val msg = "Model tidak ditemukan: ${modelFile.absolutePath}"
                    Log.e(TAG, msg)
                    notifyLoadError(msg)
                    return@execute
                }

                val loadStartMs = System.currentTimeMillis()
                val useGpu = AppPreferences(ctx).useGpuBackend
                val ok = jni.loadModel(modelFile.absolutePath, useGpu)

                val loadElapsedMs = System.currentTimeMillis() - loadStartMs
                writeDiagnostic("LOAD_MODEL_TIMING", "elapsed_ms=$loadElapsedMs ok=$ok gpu=$useGpu")
                if (ok) notifyLoadSuccess() else notifyLoadError("Gagal memuat model native")
            } catch (e: Exception) {
                notifyLoadError(e.message ?: "Error tidak diketahui")
            } finally {
                synchronized(this) { isLoading = false }
            }
        }
    }

    private fun notifyLoadSuccess() {
        val callbacks: List<LoadCallback>
        synchronized(this) {
            callbacks = pendingLoadCallbacks.toList()
            pendingLoadCallbacks.clear()
        }
        callbacks.forEach { it.onSuccess() }
    }

    private fun notifyLoadError(msg: String) {
        val callbacks: List<LoadCallback>
        synchronized(this) {
            callbacks = pendingLoadCallbacks.toList()
            pendingLoadCallbacks.clear()
        }
        callbacks.forEach { it.onError(msg) }
    }

    fun generate(
        userMessage: String,
        history: List<ChatMessage>?,
        memories: String? = null,
        deviceContext: String? = null,
        cb: GenerateCallback
    ) {
        if (!jni.isLoaded()) {
            cb.onError("Model belum dimuat")
            return
        }

        executor.execute {
            try {
                // SYN_PATCH_GEN_START
                val genStartMs = System.currentTimeMillis()
                var genTokenCount = 0
                var genFirstTokenMs = -1L
                val accumulatedResponse = StringBuilder()
                val prompt = buildPrompt(userMessage, history, memories, deviceContext)
                val grammar = SystemPromptBuilder.getToolGrammar()
                
                jni.generateStream(prompt, grammar, 512, object : LlamaJNI.StreamCallback {
                    override fun onToken(token: String) {
                        accumulatedResponse.append(token)
                        genTokenCount++ // SYN_PATCH_GEN_TOKEN
                        if (genFirstTokenMs < 0) {
                            genFirstTokenMs = System.currentTimeMillis() - genStartMs
                            writeDiagnostic("GENERATE_TIMING", "first_token_ms=$genFirstTokenMs")
                        }
                        cb.onToken(token)
                    }

                    override fun onComplete() {
                        val genElapsedMs = System.currentTimeMillis() - genStartMs
                        val tps = if (genElapsedMs > 0) (genTokenCount * 1000.0 / genElapsedMs) else 0.0
                        writeDiagnostic("GENERATE_TIMING", "tokens=$genTokenCount elapsed_ms=$genElapsedMs tps=%.2f engine=VulkanOnDemand".format(tps))
                        cb.onComplete(accumulatedResponse.toString())
                    }

                    override fun onError(message: String) {
                        // Jika terjadi crash native (GPU Error), paksa model dilepas
                        // agar pemanggilan berikutnya melakukan reload bersih.
                        jni.freeModel()
                        cb.onError(message)
                    }
                })
            } catch (e: Exception) {
                cb.onError(e.message)
            }
        }
    }

    private fun buildPrompt(userMessage: String, history: List<ChatMessage>?, memories: String?, deviceContext: String?): String {
        return buildString {
            // 1. FIXED SYSTEM PROMPT (Paling atas agar selalu kena cache)
            append("<|im_start|>system\n")
            append(SystemPromptBuilder.buildSystemPrompt())
            append("<|im_end|>\n")

            // 2. CONVERSATION HISTORY (Pruned via ContextEngine)
            val trimmedHistory = ContextEngine.compressHistory(history ?: emptyList()).takeLast(MAX_HISTORY_MESSAGES)
            var currentChars = 0
            
            trimmedHistory.asReversed().takeWhile { 
                currentChars += it.content.length
                currentChars < 5000 
            }.asReversed().forEach { msg ->
                val role = when(msg.role) {
                    "user" -> "user"
                    "assistant" -> "assistant"
                    "tool_result" -> "system" 
                    "tool_call" -> "assistant"
                    else -> "user"
                }
                append("<|im_start|>$role\n")
                if (msg.role == "tool_result") append("Tool execution result:\n")
                append(msg.content.take(MAX_HISTORY_MESSAGE_CHARS))
                append("<|im_end|>\n")
            }

            // 3. DYNAMIC CONTEXT (Memories & Device - sering berubah)
            // Diletakkan di akhir sebelum pesan user agar tidak menghanguskan cache System + History
            if (memories != null || deviceContext != null) {
                append("<|im_start|>system\n")
                if (memories != null) append("Konteks Memori:\n${memories.take(MAX_MEMORIES_CHARS)}\n")
                if (deviceContext != null) append("Konteks Perangkat:\n${deviceContext.take(MAX_DEVICE_CONTEXT_CHARS)}")
                append("<|im_end|>\n")
            }

            // 4. CURRENT USER MESSAGE
            if (userMessage.isNotEmpty()) {
                append("<|im_start|>user\n$userMessage<|im_end|>\n")
            }
            
            // Start Assistant turn
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        private const val TAG = "LlmManager"
        @Volatile
        private var instance: LlmManager? = null

        fun getInstance(): LlmManager {
            return instance ?: synchronized(this) {
                instance ?: LlmManager().also { instance = it }
            }
        }
    }
}
