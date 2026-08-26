package com.synaptic.ai.monitor

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import com.synaptic.ai.core.model.DeviceSnapshot
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class DeviceMonitor(
    context: Context,
    private val shellRunner: ((String) -> String)? = null
) {

    private val context = context.applicationContext

    private var lastProcessInfo: Pair<Int, String> = 0 to "unknown"
    private var lastProcessInfoTime: Long = 0
    
    private var lastCpuStats: Map<String, LongArray>? = null
    private var lastCpuStatsTime: Long = 0

    fun getSnapshot(): DeviceSnapshot {
        val snap = DeviceSnapshot()

        readRam(snap)
        readCpuLoad(snap)
        readTemperature(snap)
        readBattery(snap)
        readStorage(snap)
        readGpu(snap)
        readProcessInfo(snap)

        snap.uptimeMs = SystemClock.elapsedRealtime()
        return snap
    }

    private fun readRam(snap: DeviceSnapshot) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return

            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)

            snap.ramFreeBytes = info.availMem
            snap.ramTotalBytes = info.totalMem
            snap.ramUsedPercent =
                ((info.totalMem - info.availMem).toFloat() / info.totalMem.toFloat()) * 100f
        } catch (e: Exception) {
            Log.w(TAG, "RAM read gagal: ${e.message}")
        }
    }

    /**
     * Android device ini membatasi /proc/stat dari aplikasi biasa.
     * Gunakan Shizuku melalui shellRunner untuk /proc/loadavg.
     *
     * CATATAN:
     * Nilai ini adalah normalized load, BUKAN CPU utilization.
     */
    private fun writeDiagnostic(tag: String, message: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val logFile = java.io.File(dir, "diagnostic.log")
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale("id")
            ).format(java.util.Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "writeDiagnostic gagal: ${e.message}")
        }
    }

    private fun readCpuLoad(snap: DeviceSnapshot) {
        try {
            val now = SystemClock.elapsedRealtime()
            val currentCpuStats = readProcStat()
            
            if (lastCpuStats != null && currentCpuStats.isNotEmpty()) {
                val perCoreUsage = mutableListOf<Float>()
                
                // Calculate for each core
                currentCpuStats.filter { it.key.startsWith("cpu") && it.key.length > 3 }.keys.sorted().forEach { core ->
                    val cur = currentCpuStats[core]!!
                    val prev = lastCpuStats!![core] ?: longArrayOf(0, 0, 0, 0, 0, 0, 0)
                    
                    val idleDiff = cur[3] - prev[3]
                    val totalDiff = cur.sum() - prev.sum()
                    
                    if (totalDiff > 0) {
                        val usage = (1.0f - (idleDiff.toFloat() / totalDiff.toFloat())) * 100f
                        perCoreUsage.add(usage.coerceIn(0f, 100f))
                    } else {
                        perCoreUsage.add(0f)
                    }
                }
                snap.cpuPerCoreUsage = perCoreUsage
                
                // Calculate total CPU usage
                val curTotal = currentCpuStats["cpu"]!!
                val prevTotal = lastCpuStats!!["cpu"] ?: longArrayOf(0, 0, 0, 0, 0, 0, 0)
                val idleDiffTotal = curTotal[3] - prevTotal[3]
                val totalDiffTotal = curTotal.sum() - prevTotal.sum()
                if (totalDiffTotal > 0) {
                    snap.cpuUsagePercent = (1.0f - (idleDiffTotal.toFloat() / totalDiffTotal.toFloat())) * 100f
                }
            }
            
            lastCpuStats = currentCpuStats
            lastCpuStatsTime = now

            // Fallback jika /proc/stat tidak akurat (misal loadavg)
            if (snap.cpuUsagePercent <= 0f) {
                val loadAvg = try {
                    BufferedReader(FileReader("/proc/loadavg")).use { br ->
                        br.readLine()?.split(Regex("\\s+"))?.firstOrNull()?.toFloatOrNull()
                    }
                } catch (_: Exception) { null }
                
                if (loadAvg != null) {
                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    snap.cpuUsagePercent = ((loadAvg / cores) * 100f).coerceIn(0f, 100f)
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "CPU utilization gagal: ${e.message}")
            snap.cpuUsagePercent = 0.0f
        }
    }

    private fun readProcStat(): Map<String, LongArray> {
        val stats = mutableMapOf<String, LongArray>()
        try {
            val output = shellRunner?.invoke("cat /proc/stat | grep '^cpu'")
            output?.lines()?.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 5) {
                    val name = parts[0]
                    val values = parts.drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
                    if (values.isNotEmpty()) {
                        stats[name] = values
                    }
                }
            }
        } catch (_: Exception) {}
        return stats
    }

    private fun readTemperature(snap: DeviceSnapshot) {
        val shellThermal = readThermalZonesViaShell()
        if (shellThermal.isNotEmpty()) {
            val cpuCandidates = shellThermal.filter { (name, temp) ->
                temp in 1f..99f &&
                    !name.contains("battery", ignoreCase = true) &&
                    !name.contains("batt", ignoreCase = true) &&
                    !name.contains("gpu", ignoreCase = true) &&
                    (
                        name.contains("cpu", ignoreCase = true) ||
                            name.contains("soc", ignoreCase = true) ||
                            name.contains("ap", ignoreCase = true) ||
                            name.contains("xo", ignoreCase = true)
                    )
            }

            val fallbackCandidates = shellThermal.filter { (name, temp) ->
                temp in 1f..99f &&
                    !name.contains("battery", ignoreCase = true) &&
                    !name.contains("batt", ignoreCase = true) &&
                    !name.contains("gpu", ignoreCase = true)
            }

            val selected = (cpuCandidates.ifEmpty { fallbackCandidates })
                .maxByOrNull { it.second }

            if (selected != null) {
                snap.cpuTempCelsius = selected.second
                writeDiagnostic("THERMAL_CPU", "sensor=${selected.first} temp=${selected.second}")
                return
            }
        }

        val paths = arrayOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp"
        )

        for (path in paths) {
            try {
                BufferedReader(FileReader(path)).use { br ->
                    val raw = br.readLine()?.trim()?.toFloatOrNull() ?: return@use
                    val temp = if (raw > 1000f) raw / 1000f else raw

                    if (temp > 0f && temp < 100f) {
                        snap.cpuTempCelsius = temp
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readThermalZonesViaShell(): List<Pair<String, Float>> {
        val results = mutableListOf<Pair<String, Float>>()
        try {
            val thermalDir = File("/sys/class/thermal/")
            if (thermalDir.exists() && thermalDir.isDirectory) {
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
                zones?.forEach { zone ->
                    try {
                        val typeFile = File(zone, "type")
                        val tempFile = File(zone, "temp")
                        if (typeFile.exists() && tempFile.exists()) {
                            val type = typeFile.readText().trim()
                            val tempStr = tempFile.readText().trim()
                            val rawTemp = tempStr.toFloatOrNull() ?: return@forEach
                            val temp = if (rawTemp > 1000f) rawTemp / 1000f else rawTemp
                            results.add(type to temp)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal baca thermal zone via JFM: ${e.message}")
        }

        // Fallback ke shell hanya jika JFM gagal total
        if (results.isEmpty()) {
            val output = try {
                shellRunner?.invoke(
                    "for z in /sys/class/thermal/thermal_zone*; do " +
                        "t=\$(cat \"\$z/type\" 2>/dev/null); " +
                        "v=\$(cat \"\$z/temp\" 2>/dev/null); " +
                        "echo \"\$t:\$v\"; " +
                        "done"
                ).orEmpty()
            } catch (_: Exception) { "" }

            output.lines().forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    val type = parts[0].trim()
                    val raw = parts[1].trim().toFloatOrNull() ?: return@forEach
                    val temp = if (raw > 1000f) raw / 1000f else raw
                    results.add((if (type.isBlank()) "unknown" else type) to temp)
                }
            }
        }
        return results
    }

    private fun readBattery(snap: DeviceSnapshot) {
        try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return

            snap.batteryLevel =
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)

            snap.batteryTempCelsius =
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

            val status =
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            snap.isCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.w(TAG, "Battery read gagal: ${e.message}")
        }
    }

    private fun readStorage(snap: DeviceSnapshot) {
        try {
            val storage = context.filesDir
            snap.storageFreeBytes = storage.freeSpace
            snap.storageTotalBytes = storage.totalSpace
        } catch (e: Exception) {
            Log.w(TAG, "Storage read gagal: ${e.message}")
        }
    }

    private fun readGpu(snap: DeviceSnapshot) {
        fun shell(command: String): String? {
            return try {
                shellRunner?.invoke(command)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "GPU command gagal [$command]: ${e.message}")
                null
            }
        }

        snap.gpuModel =
            shell("cat /sys/class/kgsl/kgsl-3d0/gpu_model")
                ?: "unknown"

        snap.gpuBusyPercent =
            shell("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
                ?.removeSuffix("%")
                ?.trim()
                ?.toFloatOrNull()
                ?: -1f

        snap.gpuTemperatureCelsius =
            shell("cat /sys/class/kgsl/kgsl-3d0/temp")
                ?.toFloatOrNull()
                ?.let { it / 1000f }
                ?: -1f

        snap.gpuFaults =
            shell("cat /sys/class/kgsl/kgsl-3d0/gpufaults")
                ?: "unknown"

        snap.gpuFaultProcesses =
            shell("cat /sys/class/kgsl/kgsl-3d0/gpufault_procs")
                ?: "unknown"

        snap.gpuResetCount =
            shell("cat /sys/class/kgsl/kgsl-3d0/reset_count")
                ?.toLongOrNull()
                ?: -1L
    }

    private fun readProcessInfo(snap: DeviceSnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastProcessInfoTime < 5000) {
            snap.runningProcessCount = lastProcessInfo.first
            snap.foregroundApp = lastProcessInfo.second
            return
        }

        try {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val count = am?.runningAppProcesses?.size ?: 0
            snap.runningProcessCount = count

            var topPackage = "unknown"
            try {
                val usm = context?.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val end = System.currentTimeMillis()
                    val start = end - 5 * 60 * 1000L // Perkecil window ke 5 menit

                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    val top = stats?.maxByOrNull { it.lastTimeUsed }
                    if (top != null) {
                        topPackage = top.packageName
                    }
                }
            } catch (_: Exception) {}
            
            snap.foregroundApp = topPackage
            lastProcessInfo = count to topPackage
            lastProcessInfoTime = now
            
        } catch (e: Exception) {
            Log.w(TAG, "Process info gagal: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeviceMonitor"
    }
}
