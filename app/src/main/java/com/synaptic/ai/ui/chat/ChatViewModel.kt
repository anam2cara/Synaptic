package com.synaptic.ai.ui.chat

import com.synaptic.ai.llm.SystemPromptBuilder

import android.app.Application
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import android.util.Log
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.data.model.ActionLog
import com.synaptic.ai.data.model.ChatMessage
import com.synaptic.ai.data.model.Memory
import com.synaptic.ai.data.repo.SynapticDatabase
import com.synaptic.ai.llm.LlmManager
import com.synaptic.ai.tools.ToolExecutor
import com.synaptic.ai.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.Executors

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ChatViewModel"

    enum class UiState { IDLE, LOADING_MODEL, GENERATING, AWAITING_CONFIRM, ERROR }

    private val db: SynapticDatabase = (application as SynapticApp).getDatabase()!!
    private val llmManager: LlmManager = LlmManager.getInstance()
    private val toolExecutor: ToolExecutor = ToolExecutor(application)
    private val prefs: AppPreferences = AppPreferences(application)
    private val gson: Gson = Gson()
    private val backgroundExecutor = Executors.newFixedThreadPool(4)

    private val _sessionId = MutableLiveData<String>()
    val sessionId: LiveData<String> = _sessionId

    private val _uiState = MutableLiveData(UiState.IDLE)
    val uiState: LiveData<UiState> = _uiState

    private val tokenBuffer = StringBuilder()
    private val _outputFlow = MutableStateFlow("")
    val outputFlow: StateFlow<String> = _outputFlow

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _pendingAction = MutableLiveData<PendingAction?>()
    val pendingAction: LiveData<PendingAction?> = _pendingAction

    private val _sessionSummaries = MutableLiveData<List<ChatMessage>>()
    val sessionSummaries: LiveData<List<ChatMessage>> = _sessionSummaries

    val messages: LiveData<List<ChatMessage>> = _sessionId.switchMap { id ->
        db.chatDao().getMessagesLive(id)
    }

    data class PendingAction(
        val toolName: String,
        val args: String,
        val displayCommand: String
    )

    private data class SystemAction(
        val toolName: String,
        val args: String,
        val displayCommand: String,
        val description: String
    )

    // Konteks agentic yang tersimpan saat menunggu konfirmasi user
    private data class AgenticContext(
        val sessionId: String,
        val userMessage: String,
        val history: List<ChatMessage>,
        val memories: String?,
        val toolCallJson: String,
        val toolName: String,
        val args: String,
        val iteration: Int
    )
    private var pendingAgenticContext: AgenticContext? = null

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_TOOL_ITERATIONS = 3
    }
    private fun writeDiagnostic(tag: String, message: String) {
        try {
            val dir = getApplication<Application>().getExternalFilesDir(null)
                ?: return

            val logFile = java.io.File(dir, "diagnostic.log")
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale("id")
            ).format(java.util.Date())

            logFile.appendText(
                "[$timestamp] [$tag] $message\n"
            )
        } catch (e: Exception) {
            Log.e(TAG, "writeDiagnostic gagal: ${e.message}")
        }
    }
    private fun routeRequest(userMessage: String): String? {
        val text = userMessage.lowercase(Locale("id"))
        val cleaned = text.trim()

        val routedTool = when {
            cleaned.contains("batre") ||
                cleaned.contains("baterai") ||
                cleaned.contains("battery") ||
                cleaned.contains("sisa batre") ||
                cleaned.contains("sisa baterai") ||
                cleaned.contains("berapa persen") -> "device_status"

            cleaned.contains("proses") ||
                cleaned.contains("process") ||
                cleaned.contains("aplikasi boros") ||
                cleaned.contains("yang berjalan") ||
            (cleaned.contains("aplikasi") && cleaned.contains("berjalan")) ||
                cleaned.contains("berjalan di latar belakang") ||
                cleaned.contains("berjalan dilatarbelakang") ||
                (cleaned.contains("aplikasi") && cleaned.contains("latar belakang")) ||
                (cleaned.contains("app") && cleaned.contains("background")) ||
                ((cleaned.contains("app") || cleaned.contains("aplikasi")) &&
                    (cleaned.contains("ram") || cleaned.contains("boros") ||
                     cleaned.contains("paling banyak") || cleaned.contains("berat"))) -> "list_processes"

            cleaned.contains("device status") ||
                cleaned.contains("status device") ||
                cleaned.contains("cek status") ||
                cleaned.contains("cek kondisi") ||
                cleaned.contains("ram") ||
                cleaned.contains("suhu") ||
                cleaned.contains("storage") ||
                cleaned.contains("penyimpanan") -> "device_status"

            cleaned.contains("diagnosa") ||
                cleaned.contains("diagnosis") ||
                cleaned.contains("analisis") ||
                cleaned.contains("analis") ||
                cleaned.contains("kenapa lambat") ||
                cleaned.contains("kenapa lemot") ||
                cleaned.contains("device health") -> "device_analysis"

            cleaned.contains("layar") ||
                cleaned.contains("screen") ||
                cleaned.contains("tampilan") ||
                cleaned.contains("apa yang tampil") ||
                cleaned.contains("apa di layar") -> "read_screen"

            cleaned.contains("log") ||
                cleaned.contains("logcat") ||
                cleaned.contains("error") ||
                cleaned.contains("crash") ||
                cleaned.contains("stacktrace") -> "read_logs"

            else -> null
        }

        writeDiagnostic(
            "ROUTE_REQUEST",
            "userMessage=$userMessage routedTool=$routedTool"
        )

        return routedTool
    }

    private fun resolveDeviceStatusScope(userMessage: String): String {
        val text = userMessage.lowercase(Locale("id"))
        return when {
            text.contains("batre") || text.contains("baterai") || text.contains("battery") -> "battery"
            text.contains("ram") -> "ram"
            text.contains("storage") || text.contains("penyimpanan") -> "storage"
            text.contains("suhu") -> "thermal"
            else -> "all"
        }
    }

    private fun shouldAttachDeviceContext(userMessage: String): Boolean {
        val text = userMessage.lowercase(Locale("id"))
        return listOf(
            "batre", "baterai", "battery", "ram", "cpu", "suhu",
            "storage", "penyimpanan", "proses", "process", "log",
            "error", "crash", "layar", "screen", "device", "status",
            "kondisi", "analisis", "diagnosa", "diagnosis"
        ).any { text.contains(it) }
    }

    private fun shouldBypassLlmForTool(toolName: String): Boolean {
        return ToolRegistry.get(toolName)?.directRoute == true
    }

    private fun routeSystemAction(userMessage: String): SystemAction? {
        val text = userMessage.lowercase(Locale("id"))
        if (!isForceStopRequest(text)) return null

        val targetPackage = resolveAppPackage(userMessage) ?: return null
        val command = "am force-stop $targetPackage"
        val args = JSONObject()
            .put("command", command)
            .toString()

        return SystemAction(
            toolName = "shell",
            args = args,
            displayCommand = command,
            description = "Saya akan menghentikan aplikasi $targetPackage. Konfirmasi dulu sebelum perintah dijalankan."
        )
    }

    private fun isForceStopRequest(text: String): Boolean {
        return listOf(
            "force stop",
            "forcestop",
            "paksa berhenti",
            "hentikan aplikasi",
            "stop aplikasi",
            "matikan aplikasi",
            "tutup aplikasi"
        ).any { text.contains(it) }
    }

    private fun resolveAppPackage(userMessage: String): String? {
        val text = normalizeAppName(userMessage)
        val packageManager = getApplication<Application>().packageManager
        val installedApps = packageManager.getInstalledApplications(0)

        val aliases = linkedMapOf(
            "dana" to listOf("id.dana"),
            "sd maid" to listOf("eu.darken.sdmse", "eu.thedarken.sdm"),
            "sdmaid" to listOf("eu.darken.sdmse", "eu.thedarken.sdm"),
            "sd maid se" to listOf("eu.darken.sdmse"),
            "droidify" to listOf("com.looker.droidify"),
            "droid ify" to listOf("com.looker.droidify"),
            "droid-ify" to listOf("com.looker.droidify"),
            "mixplorer" to listOf("com.mixplorer.silver", "com.mixplorer"),
            "mixplorer silver" to listOf("com.mixplorer.silver"),
            "mi xplorer" to listOf("com.mixplorer.silver", "com.mixplorer"),
            "calendar" to listOf("com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"),
            "calender" to listOf("com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"),
            "kalender" to listOf("com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"),
            "tiktok" to listOf("com.zhiliaoapp.musically"),
            "tik tok" to listOf("com.zhiliaoapp.musically"),
            "whatsapp" to listOf("com.whatsapp"),
            "wa" to listOf("com.whatsapp"),
            "instagram" to listOf("com.instagram.android"),
            "ig" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana"),
            "fb" to listOf("com.facebook.katana"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "telegram" to listOf("org.telegram.messenger"),
            "shopee" to listOf("com.shopee.id"),
            "gojek" to listOf("com.gojek.app"),
            "grab" to listOf("com.grabtaxi.passenger")
        )

        aliases.firstNotNullOfOrNull { (name, packageNames) ->
            if (!text.contains(normalizeAppName(name))) {
                null
            } else {
                packageNames.firstOrNull { packageName ->
                    installedApps.any { it.packageName == packageName }
                } ?: packageNames.first()
            }
        }?.let { return it }

        val requestedName = extractRequestedAppName(text)
        val requestedCompact = requestedName.replace(" ", "")

        installedApps.firstOrNull { app ->
            app.packageName.equals(requestedName, ignoreCase = true)
        }?.let { return it.packageName }

        if (requestedName.isNotBlank()) {
            installedApps
                .sortedBy { if ((it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) 0 else 1 }
                .firstOrNull { app ->
                    val label = normalizeAppName(packageManager.getApplicationLabel(app).toString())
                    val labelTokens = label.split(" ").filter { it.isNotBlank() }.toSet()
                    val requestedTokens = requestedName.split(" ").filter { it.length > 1 }
                    val labelCompact = label.replace(" ", "")
                    val packageCompact = app.packageName.lowercase(Locale.US).replace(".", "")

                    label == requestedName ||
                        labelCompact == requestedCompact ||
                        labelCompact.startsWith(requestedCompact) ||
                        requestedTokens.isNotEmpty() && requestedTokens.all { it in labelTokens } ||
                        packageCompact.endsWith(requestedCompact)
                }?.let {
                    return it.packageName
                }

            installedApps.firstOrNull { app ->
                app.packageName.equals(requestedName, ignoreCase = true) ||
                    app.packageName.lowercase(Locale.US).endsWith(".$requestedName")
            }?.let { return it.packageName }
        }

        return null
    }

    private fun extractRequestedAppName(text: String): String {
        val markers = listOf(
            "force stop",
            "forcestop",
            "paksa berhenti",
            "hentikan aplikasi",
            "stop aplikasi",
            "matikan aplikasi",
            "tutup aplikasi"
        )

        val marker = markers.firstOrNull { text.contains(it) } ?: return ""
        return text.substringAfter(marker)
            .replace(Regex("\\b(dong|tolong|please|ya|aplikasi|app)\\b"), " ")
            .replace(Regex("[^a-z0-9._ ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizeAppName(value: String): String {
        return value.lowercase(Locale("id"))
            .replace(Regex("[^a-z0-9._ ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun formatDirectToolResponse(toolName: String, result: com.synaptic.ai.tools.ToolExecutor.ToolResult): String {
        return buildString {
            appendLine("Berikut hasilnya:")
            appendLine()
            appendLine(result.output)
            if (!result.isSuccess && result.stderr.isNotEmpty()) {
                appendLine()
                appendLine("Detail error:")
                appendLine(result.stderr)
            }
        }
    }

    // Execute tool directly without LLM roundtrip
    private fun executeToolDirect(toolName: String, currentId: String, userMessage: String) {
        Log.d(TAG, "HYBRID ROUTE: Direct execution of $toolName")
        _uiState.postValue(UiState.GENERATING)
        
        backgroundExecutor.execute {
            writeDiagnostic(
                "DIRECT_TOOL_START",
                "tool=$toolName userMessage=$userMessage"
            )

            val directArgs = if (toolName == "device_status") {
                JSONObject().put("scope", resolveDeviceStatusScope(userMessage)).toString()
            } else {
                "{}"
            }

            val result = toolExecutor.execute(toolName, directArgs)

            writeDiagnostic(
                "DIRECT_TOOL_RESULT",
                "tool=$toolName " +
                    "success=${result.isSuccess} " +
                    "exit=${result.exitCode} " +
                    "output=${result.output.replace("\n", "\\n")}"
            )
            
            val log = ActionLog("$toolName: $directArgs", result.output, result.isSuccess, currentId)
            db.actionLogDao().insert(log)
            
            val responseText = if (result.isSuccess) {
                buildString {
                    appendLine("Berikut hasilnya:")
                    appendLine()
                    appendLine(result.output)
                }
            } else {
                buildString {
                    appendLine("Gagal mengambil data (exit ${result.exitCode}):")
                    appendLine(result.output)
                    if (result.stderr.isNotEmpty()) {
                        appendLine()
                        appendLine("Detail error:")
                        appendLine(result.stderr)
                    }
                }
            }
            
            saveAssistantMessage(responseText)
            _uiState.postValue(UiState.IDLE)
        }
    }



    private var lastSanitizedLength = 0
    private var cachedSanitizedOutput = ""
    
    // Timer untuk membebaskan RAM model jika tidak digunakan (3 menit)
    private var lastActivityTime = System.currentTimeMillis()
    private val INACTIVITY_TIMEOUT = 15 * 60 * 1000L

    init {
        val app = application as SynapticApp
        val savedId = app.getSecurePrefs()?.getString("current_session_id", null)
        val initialId = savedId ?: UUID.randomUUID().toString().substring(0, 8)
        if (savedId == null) {
            app.getSecurePrefs()?.edit()?.putString("current_session_id", initialId)?.apply()
        }
        _sessionId.value = initialId
        refreshSessions()
        
        viewModelScope.launch {
            while (isActive) {
                val interval = if (_uiState.value == UiState.GENERATING) 60L else 500L
                delay(interval)
                
                // Cek Inaktivitas: Jika idle terlalu lama, bebaskan RAM
                if (_uiState.value == UiState.IDLE && llmManager.isLoaded()) {
                    if (System.currentTimeMillis() - lastActivityTime > INACTIVITY_TIMEOUT) {
                        Log.i(TAG, "Inactivity timeout: Freeing LLM RAM")
                        llmManager.freeModel()
                    }
                }
                
                synchronized(tokenBuffer) {
                    if (tokenBuffer.isNotEmpty()) {
                        // Optimasi: Hanya sanitize jika panjang buffer berubah signifikan
                        if (tokenBuffer.length != lastSanitizedLength) {
                            val rawContent = tokenBuffer.toString()
                            cachedSanitizedOutput = sanitizeLlmOutput(rawContent)
                            lastSanitizedLength = tokenBuffer.length
                        }
                        
                        if (_outputFlow.value != cachedSanitizedOutput) {
                            _outputFlow.value = cachedSanitizedOutput
                        }
                    } else if (_outputFlow.value.isNotEmpty()) {
                        _outputFlow.value = ""
                        lastSanitizedLength = 0
                        cachedSanitizedOutput = ""
                    }
                }
            }
        }
    }

    fun clearError() { _errorMessage.value = null }

    fun initModel() {
        if (llmManager.isLoaded()) { _uiState.postValue(UiState.IDLE); return }
        _uiState.postValue(UiState.LOADING_MODEL)
        llmManager.loadModel(object : LlmManager.LoadCallback {
            override fun onSuccess() { _uiState.postValue(UiState.IDLE) }
            override fun onError(msg: String) {
                _uiState.postValue(UiState.IDLE)
                Log.w(TAG, "Model load failed: $msg")
                _errorMessage.postValue("Gagal memuat model: $msg")
            }
        })
    }

    fun stopGeneration() {
        llmManager.stopGeneration()
        _uiState.postValue(UiState.IDLE)
    }

    fun regenerateLastMessage() {
        if (_uiState.value == UiState.GENERATING) return
        val currentId = _sessionId.value ?: return
        
        backgroundExecutor.execute {
            val history = db.chatDao().getMessagesSync(currentId)
            val lastUserIndex = history.findLastIndex { it.role == "user" }
            if (lastUserIndex != -1) {
                val lastUserMsg = history[lastUserIndex]
                db.chatDao().deleteMessagesAfter(currentId, lastUserMsg.timestamp + 1)
                
                synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
                _uiState.postValue(UiState.GENERATING)
                generateAiResponse(currentId, lastUserMsg.content)
            }
        }
    }

    private fun <T> List<T>.findLastIndex(predicate: (T) -> Boolean): Int {
        for (i in size - 1 downTo 0) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (_uiState.value == UiState.GENERATING) return
        
        lastActivityTime = System.currentTimeMillis()
        
        // Point 9: Cek Shizuku jika user minta perintah admin
        if (text.contains("admin", ignoreCase = true) || text.contains("shizuku", ignoreCase = true)) {
            if (!com.synaptic.ai.tools.ShizukuHelper.isShizukuAvailable()) {
                _errorMessage.value = "Peringatan: Shizuku tidak terdeteksi. Beberapa fitur admin mungkin terbatas."
            }
        }

        val currentId = _sessionId.value ?: return

        routeSystemAction(text)?.let { action ->
            val userMsg = ChatMessage(currentId, "user", text.trim())
            backgroundExecutor.execute {
                db.chatDao().insert(userMsg)
                saveAssistantMessage(action.description)
                refreshSessions()
            }
            pendingAgenticContext = null
            _pendingAction.value = PendingAction(action.toolName, action.args, action.displayCommand)
            _uiState.value = UiState.AWAITING_CONFIRM
            return
        }

        if (isForceStopRequest(text.lowercase(Locale("id")))) {
            val requestedName = extractRequestedAppName(text.lowercase(Locale("id")))
            val userMsg = ChatMessage(currentId, "user", text.trim())
            backgroundExecutor.execute {
                db.chatDao().insert(userMsg)
                saveAssistantMessage(
                    "Saya belum menemukan package untuk aplikasi ${requestedName.ifBlank { "tersebut" }}. Coba pakai nama aplikasi yang tampil di launcher atau package name langsung."
                )
                refreshSessions()
            }
            _uiState.value = UiState.IDLE
            return
        }
        
        // HYBRID ROUTER: Cek apakah bisa dijawab instan tanpa LLM
        val directTool = routeRequest(text)
        if (directTool != null) {
            val userMsg = ChatMessage(currentId, "user", text.trim())
            backgroundExecutor.execute {
                db.chatDao().insert(userMsg)
                refreshSessions()
                executeToolDirect(directTool, currentId, text)
            }
            return
        }

        synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
        _uiState.value = UiState.GENERATING
        
        backgroundExecutor.execute {
            // RELOAD ON DEMAND: Pastikan model dimuat jika sebelumnya dibebaskan
            if (!llmManager.isLoaded()) {
                _uiState.postValue(UiState.LOADING_MODEL)
                llmManager.loadModel(object : LlmManager.LoadCallback {
                    override fun onSuccess() {
                        _uiState.postValue(UiState.GENERATING)
                        val userMsg = ChatMessage(currentId, "user", text.trim())
                        db.chatDao().insert(userMsg)
                        refreshSessions()
                        generateAiResponse(currentId, text)
                    }
                    override fun onError(msg: String) {
                        _uiState.postValue(UiState.ERROR)
                        _errorMessage.postValue("Gagal memuat ulang model: $msg")
                    }
                })
            } else {
                val userMsg = ChatMessage(currentId, "user", text.trim())
                db.chatDao().insert(userMsg)
                refreshSessions()
                generateAiResponse(currentId, text)
            }
        }
    }

    fun editMessage(message: ChatMessage, newText: String) {
        if (newText.isBlank()) return
        if (_uiState.value == UiState.GENERATING) return
        val currentId = _sessionId.value ?: return
        synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
        _uiState.postValue(UiState.GENERATING)
        backgroundExecutor.execute {
            db.chatDao().deleteMessagesAfter(currentId, message.timestamp)
            val updatedMsg = ChatMessage(currentId, "user", newText.trim(), timestamp = message.timestamp)
            db.chatDao().insert(updatedMsg)
            refreshSessions()
            generateAiResponse(currentId, newText)
        }
    }

    // dropLast(1) mengecualikan pesan user yang baru saja diinsert
    // supaya tidak muncul dua kali di buildPrompt (sekali dari history, sekali dari userMessage)
    private fun generateAiResponse(currentId: String, text: String) {
        val history = db.chatDao().getMessagesSync(currentId).dropLast(1).takeLast(12)
        val memories = loadMemoriesAsText()
        val deviceContext = if (shouldAttachDeviceContext(text)) {
            toolExecutor.getDeviceDiagnosticContext()
        } else {
            null
        }
        generateWithHistory(currentId, text, history, memories, deviceContext, 0)
    }

    private fun generateWithHistory(
        currentId: String,
        userMessage: String,
        history: List<ChatMessage>,
        memories: String?,
        deviceContext: String?,
        iteration: Int
    ) {
        llmManager.generate(userMessage, history, memories, deviceContext, object : LlmManager.GenerateCallback {
            override fun onResult(result: String) {}

            override fun onToken(token: String) {
                synchronized(tokenBuffer) { tokenBuffer.append(token) }
            }

            override fun onComplete(fullResponse: String) {
                // Gunakan postValue agar aman dari thread background
                _uiState.postValue(UiState.IDLE) 
                processLlmResponse(fullResponse, currentId, userMessage, history, memories, iteration)
            }

            override fun onError(msg: String?) {
                _uiState.postValue(UiState.IDLE) // Pastikan progress bar mati jika error
                _errorMessage.postValue(msg ?: "Gagal menghasilkan respon")
            }
        })
    }

    private fun parseJsonToolCall(response: String): SystemPromptBuilder.ToolCall? {
        return try {
            if (!response.contains("\"tool\"")) return null
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val toolName = obj.get("tool")?.asString ?: return null
            val args = if (obj.has("args")) obj.get("args").toString() else "{}"
            SystemPromptBuilder.ToolCall(toolName, args)
        } catch (e: Exception) {
            null
        }
    }

    private fun processLlmResponse(
        rawResponse: String,
        currentId: String,
        userMessage: String,
        history: List<ChatMessage>,
        memories: String?,
        iteration: Int
    ) {
        val response = rawResponse.trim()

        val responseForParsing = response.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val toolCall = SystemPromptBuilder.parseToolCall(responseForParsing)
        // Also check for legacy JSON format
        val jsonToolCall = if (toolCall == null) parseJsonToolCall(responseForParsing) else null
        val finalToolCall = toolCall ?: jsonToolCall
        if (finalToolCall != null && iteration < MAX_TOOL_ITERATIONS) {
            val toolName = finalToolCall.toolName
            val args = finalToolCall.argsJson
            Log.d(TAG, "Detected TOOL: call: name=$toolName args=$args")

            if (prefs.isConfirmBeforeExec && iteration == 0) {
                pendingAgenticContext = AgenticContext(
                    currentId, userMessage, history, memories, response, toolName, args, iteration
                )
                _uiState.postValue(UiState.AWAITING_CONFIRM)
                _pendingAction.postValue(PendingAction(toolName, args, "<$toolName/>"))
                return
            }

            backgroundExecutor.execute {
                executeToolAgentic(toolName, args, currentId, userMessage, history, memories, response, iteration)
            }
            return
        }

        if (response.contains("\"tool\"") && iteration >= MAX_TOOL_ITERATIONS) {
            saveAssistantMessage("Maaf, saya tidak berhasil menyelesaikan permintaan ini setelah beberapa percobaan.")
        } else {
            saveAssistantMessage(response)
        }
        _uiState.postValue(UiState.IDLE)
    }

    private fun executeToolAgentic(
        toolName: String,
        args: String,
        currentId: String,
        userMessage: String,
        history: List<ChatMessage>,
        memories: String?,
        toolCallJson: String,
        iteration: Int
    ) {
        val result = toolExecutor.execute(toolName, args)
        Log.d(TAG, "Tool exec start: tool=$toolName argsLen=${args.length}")
        
        // Optimasi ala Hermes: Potong output yang terlalu besar sebelum masuk ke memori LLM
        val prunedOutput = com.synaptic.ai.llm.ContextEngine.pruneToolResult(toolName, result.output)
        
        val log = ActionLog("$toolName: $args", prunedOutput, result.isSuccess, currentId)
        Log.d(TAG, "Tool exec done: tool=$toolName success=${result.isSuccess} outLen=${prunedOutput.length}")
        db.actionLogDao().insert(log)

        if (shouldBypassLlmForTool(toolName)) {
            saveAssistantMessage(formatDirectToolResponse(toolName, result))
            _uiState.postValue(UiState.IDLE)
            return
        }

        // Bersihkan toolCallJson dari tag think agar history LLM tetap bersih
        val cleanToolCall = sanitizeLlmOutput(toolCallJson)

        // Semua tool (termasuk shell & list_processes) dirutekan ke LLM untuk dirangkum,
        // bukan ditampilkan mentah ke chat.
        val status = if (result.isSuccess) "OK" else "GAGAL"
        // userMessage dimasukkan ke extHistory supaya tidak di-append dua kali di buildPrompt
        val extHistory = history.toMutableList().apply {
            if (userMessage.isNotEmpty()) {
                add(ChatMessage(currentId, "user", userMessage))
            }
            add(ChatMessage(currentId, "tool_call", cleanToolCall))
            add(ChatMessage(currentId, "tool_result", "[$toolName] $status\n$prunedOutput"))
        }

        synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
        // userMessage="" karena sudah ada di extHistory, hindari duplikasi
        generateWithHistory(currentId, "", extHistory, memories, null, iteration + 1)
    }

    fun confirmAction() {
        val ctx = pendingAgenticContext
        if (ctx != null) {
            pendingAgenticContext = null
            _pendingAction.value = null
            _uiState.value = UiState.GENERATING
            backgroundExecutor.execute {
                executeToolAgentic(
                    ctx.toolName, ctx.args, ctx.sessionId,
                    ctx.userMessage, ctx.history, ctx.memories,
                    ctx.toolCallJson, ctx.iteration
                )
            }
        } else {
            val action = _pendingAction.value ?: return
            _pendingAction.value = null
            _uiState.value = UiState.GENERATING
            executeTool(action.toolName, action.args)
        }
    }

    fun rejectAction() {
        pendingAgenticContext = null
        _pendingAction.value = null
        saveAssistantMessage("Oke, saya batalkan tindakan tersebut.")
        _uiState.value = UiState.IDLE
    }

    // Dipakai sebagai fallback jika confirmAction dipanggil tanpa AgenticContext
    private fun executeTool(toolName: String, args: String) {
        val currentId = _sessionId.value ?: ""
        backgroundExecutor.execute {
            val result = toolExecutor.execute(toolName, args)
            val log = ActionLog("$toolName: $args", result.output, result.isSuccess, currentId)
            db.actionLogDao().insert(log)
            val responseText = if (result.isSuccess) "✅ " + result.output else "❌ Gagal: " + result.output
            saveAssistantMessage(responseText)
            _uiState.postValue(UiState.IDLE)
        }
    }

    private fun saveAssistantMessage(content: String) {
        val currentId = _sessionId.value ?: ""
        val msg = ChatMessage(currentId, "assistant", sanitizeLlmOutput(content))
        backgroundExecutor.execute {
            db.chatDao().insert(msg)
            synchronized(tokenBuffer) { tokenBuffer.setLength(0) }
            _outputFlow.value = ""
            refreshSessions()
        }
    }

    private fun sanitizeLlmOutput(text: String): String {
        var clean = text
        
        // Sembunyikan isi <think> jika belum ditutup
        if (clean.contains("<think>") && !clean.contains("</think>")) {
            return "..." 
        }
        
        // Buang isi <think>...</think> sepenuhnya jika sudah ditutup
        clean = clean.replace(Regex("(?s)<think>.*?</think>"), "")

        // Bersihkan tag teknis lainnya
        return clean
            .replace(Regex("TOOL:\\w+\\|\\{.*?\\}", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\|im_start\\|>.*?\\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\|im_end\\|>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\|endoftext\\|>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun loadMemoriesAsText(): String? {
        return try {
            val memories = db.memoryDao().getTopMemories(5)
            if (memories.isEmpty()) null
            else memories.joinToString("\n") { "- ${it.key}: ${it.value}" }
        } catch (e: Exception) {
            Log.w(TAG, "Memori tidak tersedia: ${e.message}")
            null
        }
    }

    fun startNewSession() {
        val newId = UUID.randomUUID().toString().substring(0, 8)
        (getApplication() as SynapticApp).getSecurePrefs()?.edit()?.putString("current_session_id", newId)?.apply()
        _sessionId.postValue(newId)
        llmManager.clearCache()
        synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
        refreshSessions()
    }

    fun loadSession(id: String) {
        (getApplication() as SynapticApp).getSecurePrefs()?.edit()?.putString("current_session_id", id)?.apply()
        _sessionId.postValue(id)
        llmManager.clearCache()
        synchronized(tokenBuffer) { tokenBuffer.setLength(0); _outputFlow.value = "" }
    }

    fun renameSession(id: String, newTitle: String) {
        backgroundExecutor.execute {
            db.chatDao().renameSession(id, newTitle)
            refreshSessions()
        }
    }

    fun deleteSession(id: String) {
        backgroundExecutor.execute {
            db.chatDao().deleteSession(id)
            if (_sessionId.value == id) startNewSession() else refreshSessions()
        }
    }

    fun refreshSessions() {
        backgroundExecutor.execute {
            val list = db.chatDao().getSessionSummariesSync()
            _sessionSummaries.postValue(list)
        }
    }

    }











