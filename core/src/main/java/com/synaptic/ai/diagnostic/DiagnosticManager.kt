package com.synaptic.ai.diagnostic

import android.content.Context
import android.os.Build
import android.util.Log
import com.synaptic.ai.core.model.DeviceSnapshot
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object DiagnosticManager {
    private const val TAG = "DiagnosticManager"
    private const val MAX_BREADCRUMBS = 100

    enum class LlmState {
        IDLE,
        MODEL_LOADING,
        MODEL_READY,
        CONTEXT_CREATING,
        READY_FOR_INFERENCE,
        INFERENCE_RUNNING,
        CANCELLING,
        UNLOADING,
        ERROR
    }

    data class Breadcrumb(
        val timestamp: Long,
        val wallClock: String,
        val threadName: String,
        val threadId: Long,
        val state: LlmState,
        val event: String,
        val metadata: String? = null
    )

    private val breadcrumbs = ConcurrentLinkedQueue<Breadcrumb>()
    private var currentState = LlmState.IDLE
    private var lastDeviceSnapshot: DeviceSnapshot? = null

    fun addBreadcrumb(event: String, metadata: String? = null) {
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val breadcrumb = Breadcrumb(
            timestamp = now,
            wallClock = dateFormat.format(Date(now)),
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            state = currentState,
            event = event,
            metadata = metadata
        )
        
        breadcrumbs.add(breadcrumb)
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.poll()
        }
        
        // Also log to Logcat for development but prefix it
        Log.d(TAG, "[DIAG] $event ${metadata ?: ""}")
    }

    fun setState(state: LlmState) {
        if (currentState != state) {
            addBreadcrumb("STATE_TRANSITION", "$currentState -> $state")
            currentState = state
        }
    }

    fun setDeviceSnapshot(snapshot: DeviceSnapshot) {
        lastDeviceSnapshot = snapshot
    }

    fun writeReport(context: Context, throwable: Throwable? = null, nativeInfo: String? = null): File? {
        try {
            val dir = File(context.filesDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = if (throwable != null) "crash_$timestamp.txt" else "report_$timestamp.txt"
            val file = File(dir, filename)

            file.printWriter().use { out ->
                out.println("=== SYNAPTIC DIAGNOSTIC REPORT ===")
                out.println("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                out.println("App Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                out.println("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                out.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                out.println("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                out.println("Current State: $currentState")
                out.println("Current Thread: ${Thread.currentThread().name} (ID: ${Thread.currentThread().id})")
                
                if (throwable != null) {
                    out.println("\n=== EXCEPTION ===")
                    out.println(throwable.javaClass.name + ": " + throwable.message)
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    out.println(sw.toString())
                }

                if (nativeInfo != null) {
                    out.println("\n=== NATIVE INFO ===")
                    out.println(nativeInfo)
                }

                out.println("\n=== LAST BREADCRUMBS ===")
                breadcrumbs.forEach { b ->
                    out.println("[${b.wallClock}] [${b.threadName}/${b.threadId}] [${b.state}] ${b.event} ${b.metadata ?: ""}")
                }

                lastDeviceSnapshot?.let { snap ->
                    out.println("\n=== MEMORY & RESOURCES ===")
                    out.println(snap.toReadableString())
                }

                out.println("\n=== THREAD SNAPSHOT ===")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    out.println("Thread: ${thread.name} (ID: ${thread.id}) state=${thread.state}")
                    if (thread == Thread.currentThread()) {
                        // Skip printing current stack trace again if it's already in the exception
                    }
                }
            }
            Log.i(TAG, "Diagnostic report written to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic report", e)
            return null
        }
    }
}
