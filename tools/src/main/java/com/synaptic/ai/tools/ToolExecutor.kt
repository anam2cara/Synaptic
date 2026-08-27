package com.synaptic.ai.tools

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.monitor.DeviceMonitor
import com.synaptic.ai.diagnostic.PerformanceAnalyzer
import com.synaptic.ai.accessibility.SynapticAccessibilityService
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ToolExecutor(context: Context) {

    private val context: Context = context.applicationContext

    private val deviceMonitor = DeviceMonitor(
        context,
        shellRunner = { command: String -> ShellExecutor.run(command) }
    )
    
    fun getDeviceMonitor(): DeviceMonitor = deviceMonitor

    private val performanceAnalyzer = PerformanceAnalyzer()
    private val prefs = AppPreferences(context)

    private var cachedUserPackages: Map<Int, Set<String>>? = null
    private var lastPackageCacheTime: Long = 0

    interface ToolResult {
        val output: String
        val isSuccess: Boolean
        val stderr: String
        val exitCode: Int
    }

    fun execute(toolName: String, args: String): ToolResult {
        val normalizedName = toolName.trim().lowercase()
        val definition = ToolRegistry.get(normalizedName)

        Log.d(
            TAG,
            "EXECUTE => tool=$normalizedName permission=${definition?.permission}"
        )

        if (definition == null) {
            return makeResult(
                false,
                "Tool tidak dikenal: $normalizedName",
                "",
                -1
            )
        }

        val validationError = ToolRegistry.validate(normalizedName, args)
        if (validationError != null) {
            return makeResult(false, validationError, "", -1)
        }

        return when (normalizedName) {
            "device_status" -> executeDeviceStatus(args)
            "device_analysis" -> executeDeviceAnalysis()
            "shell" -> executeShell(extractJsonString(args, "command"))
            "python" -> executePython(extractJsonString(args, "code"))
            "list_processes" -> executeListProcesses()
            "read_screen" -> executeReadScreen()
            "read_logs" -> executeReadLogs()
            "native_backend_status" -> executeNativeBackendStatus()
            "pgvector_status" -> executePgVectorStatus()
            "n8n_status" -> executeN8nStatus()
            "n8n_trigger" -> executeN8nTrigger(extractJsonString(args, "payload"))
            else -> makeResult(false, "Tool belum memiliki executor: $normalizedName", "", -1)
        }
    }

    private fun extractJsonString(args: String, key: String): String {
        return try {
            val obj = org.json.JSONObject(args)
            obj.optString(key, "")
        } catch (_: Exception) {
            ""
        }
    }

    fun getToolDefinition(toolName: String): ToolRegistry.ToolDefinition? {
        return ToolRegistry.get(toolName)
    }

    fun requiresConfirmation(toolName: String): Boolean {
        return ToolRegistry.get(toolName)?.requiresConfirmation == true
    }

    fun requiresShizuku(toolName: String): Boolean {
        return ToolRegistry.get(toolName)?.permission == ToolRegistry.Permission.SHIZUKU
    }
    private fun executePython(code: String): ToolResult {
        if (code.isBlank()) {
            return makeResult(false, "Kode Python kosong.", "", -1)
        }

        val escapedCode = code.replace("'", "'\\''")
        val command = "sh -c \"python3 -c '$escapedCode'\""
        Log.d(TAG, "Executing Python code via Shizuku")

        return executeShell(command)
    }

    private fun executeReadLogs(): ToolResult {
        return executeShell("logcat -d -v brief *:E *:W | tail -n 50")
    }

    private fun executeNativeBackendStatus(): ToolResult {
        val output = buildString {
            appendLine("LLM lokal: on-demand")
            appendLine("Server llama.cpp: nonaktif")
            appendLine("Backend GPU: ${if (prefs.useGpuBackend) "Vulkan aktif" else "dinonaktifkan dari preferensi"}")
            appendLine("Vulkan debug: OFF pada build script")
            appendLine("OpenCL: didukung jika libggml-opencl.so tersedia di APK")
            appendLine("Mode eksekusi: native JNI langsung, bukan koneksi server")
        }
        return makeResult(true, output, "", 0)
    }

    private fun executePgVectorStatus(): ToolResult {
        val configured = prefs.pgVectorUrl.isNotBlank()
        val output = buildString {
            appendLine("PostgreSQL/pgVector: ${if (configured) "terkonfigurasi" else "belum dikonfigurasi"}")
            appendLine("URL: ${prefs.pgVectorUrl.ifBlank { "(kosong)" }}")
            appendLine("API key: ${if (prefs.pgVectorApiKey.isBlank()) "(kosong)" else "tersimpan"}")
            appendLine("Catatan: integrasi ini opsional dan hanya dipakai saat diminta, bukan background service.")
        }
        return makeResult(true, output, "", 0)
    }

    private fun executeN8nStatus(): ToolResult {
        val configured = prefs.n8nWebhookUrl.isNotBlank()
        val output = buildString {
            appendLine("n8n: ${if (configured) "terkonfigurasi" else "belum dikonfigurasi"}")
            appendLine("Webhook: ${prefs.n8nWebhookUrl.ifBlank { "(kosong)" }}")
            appendLine("API key: ${if (prefs.n8nApiKey.isBlank()) "(kosong)" else "tersimpan"}")
            appendLine("Catatan: webhook hanya dipanggil setelah aksi dikonfirmasi.")
        }
        return makeResult(true, output, "", 0)
    }

    private fun executeN8nTrigger(payload: String): ToolResult {
        val webhook = prefs.n8nWebhookUrl
        if (webhook.isBlank()) {
            return makeResult(false, "Webhook n8n belum dikonfigurasi.", "", 1)
        }

        return try {
            val connection = (URL(webhook).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (prefs.n8nApiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${prefs.n8nApiKey}")
                }
            }

            val body = if (payload.trim().startsWith("{")) payload else """{"message":${org.json.JSONObject.quote(payload)}}"""
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            makeResult(
                code in 200..299,
                if (response.isBlank()) "n8n HTTP $code" else "n8n HTTP $code\n$response",
                "",
                code
            )
        } catch (e: Exception) {
            makeResult(false, "Gagal memanggil n8n: ${e.message}", e.stackTraceToString(), 1)
        }
    }

    private fun executeDeviceStatus(args: String): ToolResult {
        return try {
            val snap = deviceMonitor.getSnapshot()
            val scope = extractJsonString(args, "scope").lowercase()

            val output = when (scope) {
                "battery" -> snap.toBatteryString()
                "ram" -> snap.toRamString()
                "storage" -> snap.toStorageString()
                "thermal" -> snap.toThermalString()
                else -> snap.toReadableString()
            }

            makeResult(true, output, "", 0)

        } catch (e: Exception) {
            makeResult(
                false,
                "Gagal baca status device: ${e.message}",
                e.stackTraceToString(),
                1
            )
        }
    }

    private fun executeShell(command: String?): ToolResult {
        if (command.isNullOrBlank()) {
            return makeResult(false, "Perintah kosong.")
        }

        for (blocked in HARDCODED_BLACKLIST) {
            if (command.contains(blocked)) {
                return makeResult(
                    false,
                    "Perintah diblokir karena berbahaya: $blocked"
                )
            }
        }

        val res = ShellExecutor.runWithResult(command)

        val resultText =
            if (res.output.isNotEmpty()) {
                res.output
            } else if (res.isSuccess) {
                "(Berhasil, tanpa output)"
            } else {
                "(Gagal, tanpa output)"
            }

        return makeResult(
            res.isSuccess,
            resultText,
            "",
            res.exitCode
        )
    }

    /**
     * Mengambil evidence proses secara realtime.
     *
     * Sumber utama:
     * - PackageManager: menentukan package non-system/user-installed.
     * - ActivityManager: proses yang terlihat oleh framework Android.
     *
     * Fallback:
     * - Shizuku/ps untuk mendapatkan proses tambahan yang tidak terlihat
     *   dari ActivityManager.
     *
     * Tidak ada interpretasi heuristik RSS atau daftar package hardcoded.
     */
    private fun executeListProcesses(): ToolResult {
        return try {
            val pm = context.packageManager
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

            val userPackagesByUid = if (cachedUserPackages != null && System.currentTimeMillis() - lastPackageCacheTime < 60000) {
                cachedUserPackages!!
            } else {
                val map = mutableMapOf<Int, MutableSet<String>>()
                try {
                    val apps = pm.getInstalledApplications(0)
                    for (app in apps) {
                        val flags = app.flags
                        val isUserInstalled = (flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                                              (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        if (isUserInstalled) {
                            map.getOrPut(app.uid) { mutableSetOf() }.add(app.packageName)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal ambil daftar aplikasi: ${e.message}")
                }
                cachedUserPackages = map
                lastPackageCacheTime = System.currentTimeMillis()
                map
            }

            data class ProcessEvidence(
                val pid: Int,
                val uid: Int,
                val processName: String,
                val packageNames: List<String>,
                val state: String,
                val source: String
            )

            val evidence = linkedMapOf<String, ProcessEvidence>()

            // SOURCE 1: ActivityManager
            try {
                val runningProcesses = am?.runningAppProcesses.orEmpty()

                for (process in runningProcesses) {
                    val packages = userPackagesByUid[process.uid]
                        ?.toList()
                        .orEmpty()

                    if (packages.isEmpty()) {
                        continue
                    }

                    val state = when (process.importance) {
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                            "FOREGROUND"

                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                            "FOREGROUND_SERVICE"

                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                            "VISIBLE"

                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE ->
                            "PERCEPTIBLE"

                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ->
                            "SERVICE"

                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ->
                            "CACHED"

                        else ->
                            "UNKNOWN"
                    }

                    val key = "${process.uid}:${process.pid}"

                    evidence[key] = ProcessEvidence(
                        pid = process.pid,
                        uid = process.uid,
                        processName = process.processName,
                        packageNames = packages,
                        state = state,
                        source = "ActivityManager"
                    )
                }

            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "ActivityManager process query gagal: ${e.message}"
                )
            }

            // SOURCE 2: Shizuku fallback (Hanya jika ActivityManager tidak memberikan data atau ingin lebih detail)
            // Optimasi: Lewati jika sudah ada evidence foreground dari AM untuk menghemat waktu
            val hasForeground = evidence.values.any { it.state == "FOREGROUND" }
            if (!hasForeground) {
                try {
                    val shell = ShellExecutor.runWithResult(
                        "ps -A -o pid,uid,stat,name"
                    )

                    if (shell.isSuccess && shell.output.isNotBlank()) {
                        for (line in shell.output.lines().drop(1)) {
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size < 4) continue

                            val pid = parts[0].toIntOrNull() ?: continue
                            val uid = parts[1].toIntOrNull() ?: continue
                            val stat = parts[2]
                            val processName = parts.drop(3).joinToString(" ")

                            val packages = userPackagesByUid[uid]?.toList().orEmpty()
                            if (packages.isEmpty()) continue

                            val key = "$uid:$pid"
                            if (!evidence.containsKey(key)) {
                                evidence[key] = ProcessEvidence(
                                    pid = pid,
                                    uid = uid,
                                    processName = processName,
                                    packageNames = packages,
                                    state = stat,
                                    source = "Shizuku/ps"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku process fallback gagal: ${e.message}")
                }
            }

            val output = buildString {
                appendLine("[REALTIME_ANDROID_PROCESS_EVIDENCE]")
                appendLine(
                    "SOURCE=PackageManager + ActivityManager + ShizukuFallback"
                )
                appendLine(
                    "USER_APP_PROCESS_COUNT=${evidence.size}"
                )
                appendLine()

                if (evidence.isEmpty()) {
                    appendLine("STATUS=NO_USER_APP_PROCESS_DATA")
                    appendLine(
                        "MESSAGE=Android tidak memberikan data proses " +
                        "user-app yang dapat diverifikasi."
                    )
                } else {
                    // Optimasi: Urutkan dan batasi agar tidak membebani context window LLM
                    val sortedEvidence = evidence.values
                        .sortedWith(
                            compareByDescending<ProcessEvidence> { it.state == "FOREGROUND" }
                                .thenByDescending { it.state == "FOREGROUND_SERVICE" }
                                .thenBy { it.packageNames.joinToString(",") }
                        )
                        .take(25)

                    sortedEvidence.forEach { process ->
                        appendLine(
                            "PID=${process.pid} " +
                            "UID=${process.uid} " +
                            "PROCESS=${process.processName} " +
                            "PACKAGES=${process.packageNames.joinToString(",")} " +
                            "STATE=${process.state} " +
                            "SOURCE=${process.source}"
                        )
                    }

                    if (evidence.size > 25) {
                        appendLine("... (dan ${evidence.size - 25} proses lainnya diabaikan demi efisiensi)")
                    }
                }

                appendLine()
                appendLine(
                    "RULE=Hanya package non-system yang memiliki " +
                    "proses aktif pada saat query."
                )
            }

            makeResult(true, output, "", 0)

        } catch (e: Exception) {
            Log.e(TAG, "list_processes gagal", e)

            makeResult(
                false,
                "REALTIME_PROCESS_QUERY_FAILED: ${e.message}",
                e.stackTraceToString(),
                1
            )
        }
    }

    private fun executeReadScreen(): ToolResult {
        Log.d("SynapticA11y", "executeReadScreen CALLED")

        val service = SynapticAccessibilityService.instance

        if (service == null) {
            Log.d("SynapticA11y", "Accessibility Service NULL")

            return makeResult(
                false,
                "Accessibility Service belum aktif."
            )
        }

        val dump = service.dumpCurrentScreen()

        Log.d(
            "SynapticA11y",
            "SCREEN_DUMP:\n$dump"
        )

        return makeResult(true, dump)
    }

    private fun executeDeviceAnalysis(): ToolResult {
        return try {
            val snapshot = deviceMonitor.getSnapshot()
            val analysis = performanceAnalyzer.analyze(snapshot)

            val text = buildString {
                appendLine("Severity: ${analysis.severity}")
                appendLine()
                appendLine(analysis.summary)

                if (analysis.recommendations.isNotEmpty()) {
                    appendLine()
                    appendLine("Rekomendasi:")

                    analysis.recommendations.forEach {
                        appendLine("- $it")
                    }
                }
            }

            makeResult(true, text, "", 0)

        } catch (e: Exception) {
            makeResult(
                false,
                "Analisis gagal: ${e.message}"
            )
        }
    }

    private fun makeResult(
        success: Boolean,
        outputText: String,
        stderrText: String = "",
        exitCodeVal: Int = if (success) 0 else 1
    ): ToolResult {
        return object : ToolResult {
            override val output: String = outputText
            override val isSuccess: Boolean = success
            override val stderr: String = stderrText
            override val exitCode: Int = exitCodeVal
        }
    }

    fun getDeviceDiagnosticContext(): String {
        val s = deviceMonitor.getSnapshot()
        // Menggunakan field yang ada di DeviceSnapshot
        val ramUsedGb = (s.ramTotalBytes - s.ramFreeBytes) / 1e9f
        val ramTotalGb = s.ramTotalBytes / 1e9f
        val storageFreeGb = s.storageFreeBytes / 1e9f
        val storageTotalGb = s.storageTotalBytes / 1e9f
        
        return """
[REALTIME_SYSTEM_EVIDENCE]
BATERAI: ${s.batteryLevel}% (Charging=${s.isCharging})
MEMORI: ${"%.1f".format(ramUsedGb)}GB/${"%.1f".format(ramTotalGb)}GB (${"%.1f".format(s.ramUsedPercent)}%)
STORAGE: ${"%.1f".format(storageFreeGb)}GB/${"%.1f".format(storageTotalGb)}GB free
CPU_SUHU: ${s.cpuTempCelsius}°C
GPU_LOAD: ${if (s.gpuBusyPercent >= 0) "${s.gpuBusyPercent}%" else "N/A"} (${s.gpuTemperatureCelsius}°C)
AKTIF: ${s.foregroundApp}
""".trimIndent()
    }

    companion object {
        private const val TAG = "ToolExecutor"

        private val HARDCODED_BLACKLIST = setOf(
            "rm -rf /",
            "rm -rf /*",
            "format",
            "mkfs",
            "dd if=",
            ":(){ :|:& };:",
            "chmod 777 /",
            "reboot",
            "shutdown"
        )
    }
}

