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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.synaptic.ai.diagnostic.DiagnosticManager
import com.synaptic.ai.diagnostic.DiagnosticManager.LlmState

    private const val SYNAPTIC_MODEL_PATH = "/storage/emulated/0/Documents/Berkas_lain/LLM_model/Qwen3-0.6B-Q4_K_M.gguf"
    private const val IDLE_UNLOAD_TIMEOUT_MS = 120_000L // 2 menit

class LlmManager private constructor() {
    private var context: Context? = null
    private val jni: NativeBridge = LlamaBridge()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val idleScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var idleUnloadTask: ScheduledFuture<*>? = null
    
    var isLoading: Boolean = false
        private set

    data class ModelInfo(val name: String, val useGpu: Boolean, val path: String, val role: String = "primary")
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

    private data class ModelProfile(
        val role: String,
        val path: String,
        val label: String,
        val defaultMaxTokens: Int
    )

    private data class LoadResult(
        val success: Boolean,
        val message: String = ""
    )

    private val activeModel = ModelProfile(
        role = "reasoning",
        path = SYNAPTIC_MODEL_PATH,
        label = "Qwen3-0.6B Q4_K_M",
        defaultMaxTokens = 768
    )

    fun init(ctx: Context) {
        this.context = ctx.applicationContext
    }

    fun onTrimMemory(level: Int) {
        DiagnosticManager.addBreadcrumb("TRIM_MEMORY", "level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            if (isLoaded() && !isLoading) {
                freeModel()
            }
        }
    }

    fun isLoaded(): Boolean = jni.isLoaded()
    fun clearCache() { jni.clearCache() }
    fun stopGeneration() { jni.stopGeneration() }
    fun freeModel() {
        executor.execute {
            freeModelInternal()
        }
    }

    private fun freeModelInternal() {
        try {
            DiagnosticManager.setState(LlmState.UNLOADING)

            if (jni.isLoaded()) {
                jni.freeModel()
            }

            loadedModelInfo = null
            Log.i("LlmManager", "Model dibebaskan dari RAM")
            DiagnosticManager.setState(LlmState.IDLE)
        } catch (e: Exception) {
            loadedModelInfo = null

            DiagnosticManager.addBreadcrumb(
                "FREE_MODEL_EXCEPTION",
                e.message ?: "unknown"
            )

            DiagnosticManager.setState(LlmState.ERROR)
            Log.e("LlmManager", "freeModel gagal", e)
        }
    }

    private fun findModelInCommonFolders(fileName: String): File? {
        val root = Environment.getExternalStorageDirectory().path
        val commonFolders = listOf(
            "$root/Documents/Berkas_lain/LLM_model",
            "$root/Documents/Berkas_lain/LLM model",
            "$root/Download",
            "$root/models"
        )
        for (path in commonFolders) {
            val file = File(path, fileName)
            if (file.exists() && file.canRead()) return file
        }
        return null
    }

    /**
     * Auto Model Manager: Menentukan kelayakan model berdasarkan spesifikasi hardware.
     */
    private object AutoModelManager {
        data class ModelConfig(
            val canRun: Boolean,
            val reason: String,
            val nCtx: Int,
            val nGpuLayers: Int
        )

        fun analyze(context: Context, jni: NativeBridge, fixedModelPath: String, userGpuPref: Boolean): ModelConfig {
            val meta = jni.getModelMetadata(fixedModelPath) ?: return ModelConfig(false, "Gagal membaca metadata model.", 256, 0)
            
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            
            val totalRamGb = memInfo.totalMem / 1e9
            val availRamGb = memInfo.availMem / 1e9
            val modelSizeGb = meta.totalSize / 1e9
            
            Log.i("AutoModelManager", "Probing Model: ${meta.description}, Size: ${"%.2f".format(modelSizeGb)}GB, Total RAM: ${"%.1f".format(totalRamGb)}GB")

            // Reserve 2.5GB untuk OS + Apps
            if (modelSizeGb > totalRamGb - 2.5) {
                return ModelConfig(false, "Model (${"%.1f".format(modelSizeGb)}GB) terlalu besar untuk RAM HP Anda.", 256, 0)
            }

            var recommendedCtx = 1024
            var gpuLayers = 0

            when {
                modelSizeGb > 2.5 -> { // Model berat (misal 4B)
                    recommendedCtx = if (availRamGb > 1.2) 512 else 256
                    gpuLayers = if (userGpuPref) 2 else 0
                }
                modelSizeGb > 1.0 -> { // Model sedang (misal 1.7B)
                    recommendedCtx = if (availRamGb > 1.8) 1024 else 512
                    gpuLayers = if (userGpuPref) 4 else 0
                }
                else -> { // Model ringan (misal 0.6B)
                    recommendedCtx = if (availRamGb > 2.2) 2048 else 1024
                    gpuLayers = if (userGpuPref) 8 else 0
                }
            }
            
            return ModelConfig(true, "OK", recommendedCtx, gpuLayers)
        }
    }

    fun loadModel(cb: LoadCallback) {
        Log.i("LlmManager", "Preloading model: ${activeModel.path}")
        val ctx = context ?: run { cb.onError("LlmManager belum diinisialisasi"); return }
        val targetPath = activeModel.path
        val useGpu = AppPreferences(ctx).useGpuBackend

        DiagnosticManager.addBreadcrumb("MODEL_LOAD_REQUEST", "path=$targetPath gpu=$useGpu")

        if (isLoaded() && loadedModelInfo?.path == targetPath && loadedModelInfo?.useGpu == useGpu) {
            DiagnosticManager.addBreadcrumb("MODEL_LOAD_SKIPPED", "Already loaded")
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
                val result = loadProfileInternal(ctx, activeModel, useGpu)
                if (result.success) {
                    notifyLoadSuccess()
                } else {
                    notifyLoadError(result.message)
                }
            } catch (e: Exception) {
                DiagnosticManager.addBreadcrumb("MODEL_LOAD_EXCEPTION", e.message)
                DiagnosticManager.setState(LlmState.ERROR)
                notifyLoadError("ERROR: ${e.message}")
            } finally {
                synchronized(this) { isLoading = false }
            }
        }
    }

    private fun loadProfileInternal(ctx: Context, profile: ModelProfile, useGpu: Boolean): LoadResult {
        val requestedFile = File(profile.path)
        val modelFile = if (requestedFile.exists() && requestedFile.canRead()) {
            requestedFile
        } else {
            findModelInCommonFolders(requestedFile.name)
                ?: run {
                val isManager = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
                val msg = if (!isManager) {
                    "AKSES DITOLAK: Aktifkan 'All Files Access' di Settings."
                } else {
                    "MODEL TIDAK DITEMUKAN: ${requestedFile.name}"
                }
                DiagnosticManager.addBreadcrumb("MODEL_LOAD_ERROR", msg)
                DiagnosticManager.setState(LlmState.ERROR)
                return LoadResult(false, msg)
            }
        }

        if (isLoaded() && loadedModelInfo?.path == modelFile.absolutePath && loadedModelInfo?.useGpu == useGpu) {
            DiagnosticManager.addBreadcrumb("MODEL_LOAD_SKIPPED", "Already loaded ${profile.role}")
            return LoadResult(true)
        }

        DiagnosticManager.setState(LlmState.MODEL_LOADING)
        freeModelInternal()

        val config = AutoModelManager.analyze(ctx, jni, modelFile.absolutePath, useGpu)
        if (!config.canRun) {
            DiagnosticManager.addBreadcrumb("MODEL_LOAD_REJECTED", config.reason)
            DiagnosticManager.setState(LlmState.ERROR)
            return LoadResult(false, config.reason)
        }

        Log.i("LlmManager", "Loading ${profile.label}: ctx=${config.nCtx}, gpu=${config.nGpuLayers}")
        DiagnosticManager.addBreadcrumb(
            "NATIVE_LOAD_START",
            "role=${profile.role} ctx=${config.nCtx} gpu=${config.nGpuLayers}"
        )

        var success = jni.loadModel(modelFile.absolutePath, config.nGpuLayers, config.nCtx)

        if (!success && config.nGpuLayers > 0) {
            Log.w("LlmManager", "GPU load failed for ${profile.label}, falling back to CPU.")
            DiagnosticManager.addBreadcrumb("NATIVE_LOAD_FALLBACK", "role=${profile.role} cpu")
            success = jni.loadModel(modelFile.absolutePath, 0, config.nCtx)
            if (success) AppPreferences(ctx).useGpuBackend = false
        }

        return if (success) {
            DiagnosticManager.addBreadcrumb("MODEL_LOAD_SUCCESS", "role=${profile.role}")
            loadedModelInfo = ModelInfo(
                name = modelFile.name,
                useGpu = AppPreferences(ctx).useGpuBackend,
                path = modelFile.absolutePath,
                role = profile.role
            )
            DiagnosticManager.setState(LlmState.MODEL_READY)
            LoadResult(true)
        } else {
            DiagnosticManager.addBreadcrumb("MODEL_LOAD_FAILED", "role=${profile.role}")
            DiagnosticManager.setState(LlmState.ERROR)
            LoadResult(false, "ENGINE GAGAL: Hardware HP Anda tidak sanggup memuat ${profile.label}.")
        }
    }

        private fun scheduleIdleUnload() {
        synchronized(this) {
            idleUnloadTask?.cancel(false)
            idleUnloadTask = idleScheduler.schedule({
                Log.i("LlmManager", "Idle timeout reached, unloading model")
                DiagnosticManager.addBreadcrumb("IDLE_UNLOAD", "timeout=${IDLE_UNLOAD_TIMEOUT_MS}ms")
                freeModel()
            }, IDLE_UNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelIdleUnload() {
        synchronized(this) {
            idleUnloadTask?.cancel(false)
            idleUnloadTask = null
        }
    }
private fun notifyLoadSuccess() {
        val callbacks: List<LoadCallback>
        synchronized(this) { callbacks = pendingLoadCallbacks.toList(); pendingLoadCallbacks.clear() }
        callbacks.forEach { it.onSuccess() }
        scheduleIdleUnload()
    }

    private fun notifyLoadError(msg: String) {
        val callbacks: List<LoadCallback>
        synchronized(this) { callbacks = pendingLoadCallbacks.toList(); pendingLoadCallbacks.clear() }
        callbacks.forEach { it.onError(msg) }
    }

    fun generate(userMessage: String, history: List<ChatMessage>?, memories: String? = null, deviceContext: String? = null, cb: GenerateCallback) {
        cancelIdleUnload()
        val ctx = context ?: run { cb.onError("LlmManager belum diinisialisasi"); return }
        val profile = activeModel
        DiagnosticManager.addBreadcrumb("GENERATE_REQUEST", "msgLen=${userMessage.length} role=${profile.role}")
        executor.execute {
            try {
                val useGpu = AppPreferences(ctx).useGpuBackend
                val loadResult = loadProfileInternal(ctx, profile, useGpu)
                if (!loadResult.success) {
                    cb.onError(loadResult.message)
                    return@execute
                }

                DiagnosticManager.setState(LlmState.INFERENCE_RUNNING)
                val prompt = buildPrompt(userMessage, history, memories, deviceContext)
                val grammar = SystemPromptBuilder.getToolGrammar()
                val responseBuffer = StringBuilder()
                
                jni.generateStream(prompt, grammar, profile.defaultMaxTokens, object : LlamaJNI.StreamCallback {
                    override fun onToken(token: String) {
                        responseBuffer.append(token)
                        cb.onToken(token)
                    }
                    override fun onComplete() { 
                        val fullResponse = responseBuffer.toString()
                        DiagnosticManager.addBreadcrumb("GENERATE_SUCCESS", "role=${profile.role} tokensTextLen=${fullResponse.length}")
                        DiagnosticManager.setState(LlmState.MODEL_READY)
                        cb.onComplete(fullResponse)
                    scheduleIdleUnload()
                    }
                    override fun onError(message: String) {
                        DiagnosticManager.addBreadcrumb("GENERATE_ERROR", message)
                        executor.execute {

                            freeModelInternal()

                        }
                        loadedModelInfo = null
                        DiagnosticManager.setState(LlmState.ERROR)
                        if (message.contains("vk", ignoreCase = true) || message.contains("Device", ignoreCase = true) || message.contains("pipeline", ignoreCase = true)) {
                            context?.let { AppPreferences(it).useGpuBackend = false }
                            cb.onError("GPU LIMIT: Beralih ke CPU (Stabil)...")
                        } else {
                            cb.onError("HP KRITIS: RAM sisa sedikit. Tutup aplikasi lain.")
                        }
                    }
                })
            } catch (e: Exception) { 
                DiagnosticManager.addBreadcrumb("GENERATE_EXCEPTION", e.message)
                DiagnosticManager.setState(LlmState.ERROR)
                cb.onError("Generate Error: ${e.message}") 
            }
        }
    }


    private fun buildPrompt(userMessage: String, history: List<ChatMessage>?, memories: String?, deviceContext: String?): String {
        return buildString {
            append("<|im_start|>system\n${SystemPromptBuilder.buildSystemPrompt(memories)}<|im_end|>\n")
            // Gunakan riwayat minimal untuk hemat RAM
            val trimmedHistory = (history ?: emptyList()).takeLast(2)
            trimmedHistory.forEach { msg ->
                val role = when(msg.role) { "user"->"user"; "assistant"->"assistant"; "tool_result"->"system"; "tool_call"->"assistant"; else->"user" }
                append("<|im_start|>$role\n${msg.content.take(300)}<|im_end|>\n")
            }
            if (deviceContext != null) {
                append("<|im_start|>system\nHP_STATUS: $deviceContext<|im_end|>\n")
            }
            if (userMessage.isNotEmpty()) append("<|im_start|>user\n$userMessage<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        @Volatile private var instance: LlmManager? = null
        fun getInstance(): LlmManager = instance ?: synchronized(this) { instance ?: LlmManager().also { instance = it } }
    }
}



