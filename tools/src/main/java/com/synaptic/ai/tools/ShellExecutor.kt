package com.synaptic.ai.tools

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellExecutor {

    private const val TAG = "ShellExecutor"

    data class Result(
        val output: String,
        val isSuccess: Boolean,
        val exitCode: Int,
        val isTimeout: Boolean = false
    )

    fun run(command: String): String {
        return runWithResult(command).output
    }

    fun runWithResult(command: String): Result {
        val timeoutMs = resolveTimeout(command)
        
        // Coba jalankan via Shizuku jika tersedia dan diizinkan
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasPermission()) {
            return try {
                Log.d(TAG, "Menjalankan via Shizuku: $command")
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                readProcessOutput(process, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal via Shizuku, fallback ke Runtime.exec: ${e.message}")
                runNormal(command)
            }
        }
        return runNormal(command)
    }

    private fun runNormal(command: String): Result {
        return try {
            Log.d(TAG, "Menjalankan via Runtime.exec: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            readProcessOutput(process, resolveTimeout(command))
        } catch (e: Exception) {
            Result(e.message ?: "error", false, -1)
        }
    }

    private fun resolveTimeout(command: String): Long {
        val lower = command.lowercase()
        return when {
            "dumpsys battery" in lower -> 2000L
            "logcat" in lower -> 3000L
            "top " in lower || lower.startsWith("top") -> 2500L
            lower.startsWith("ps ") || " ps " in lower -> 2500L
            else -> 5000L
        }
    }

    private fun readProcessOutput(process: Process, timeoutMs: Long): Result {
        val output = StringBuilder()
        val error = StringBuilder()
        val start = System.currentTimeMillis()

        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            } catch (_: Exception) {}
        }

        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { error.appendLine(it) }
                }
            } catch (_: Exception) {}
        }

        outThread.start()
        errThread.start()

        var exitCode = -1

        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (_: RuntimeException) {
                    Thread.sleep(50)
                }
            }

            try {
                exitCode = process.exitValue()
            } catch (_: RuntimeException) {
                try { process.destroyForcibly() } catch (_: Exception) {}
                outThread.join(500)
                errThread.join(500)

                return Result(
                    output.toString().trim(),
                    false,
                    -1,
                    true
                )
            }

            outThread.join(500)
            errThread.join(500)

            val errText = error.toString().trim()
            val combined = buildString {
                append(output.toString().trim())
                if (errText.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(errText)
                }
            }

            return Result(
                combined,
                exitCode == 0,
                exitCode,
                false
            )

        } catch (e: Exception) {
            try { process.destroyForcibly() } catch (_: Exception) {}
            return Result(e.message ?: "process error", false, -1)
        } finally {
            try { process.destroy() } catch (_: Exception) {}
        }
    }
}

