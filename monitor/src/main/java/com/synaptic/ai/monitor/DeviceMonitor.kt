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
import java.io.FileReader

class DeviceMonitor(
    context: Context,
    private val shellRunner: ((String) -> String)? = null
) {

    private val context = context.applicationContext

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
        private fun readCpuLoad(snap: DeviceSnapshot) {
        try {
            val output = shellRunner?.invoke(
                "top -n 1 -b | grep '%cpu' | head -n 1"
            )?.trim().orEmpty()

            val match = Regex(
                """(\d+)%cpu\s+(\d+)%user\s+(\d+)%nice\s+(\d+)%sys\s+(\d+)%idle\s+(\d+)%iow\s+(\d+)%irq\s+(\d+)%sirq\s+(\d+)%host"""
            ).find(output)

            if (match != null) {
                val total = match.groupValues[1].toFloat()
                val idle = match.groupValues[5].toFloat()

                snap.cpuUsagePercent =
                    ((total - idle) / total * 100f).coerceIn(0f, 100f)
            } else {
                snap.cpuUsagePercent = -1f
            }
        } catch (e: Exception) {
            Log.w(TAG, "CPU utilization gagal: ${e.message}")
            snap.cpuUsagePercent = -1f
        }
    }

    private fun readTemperature(snap: DeviceSnapshot) {
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
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

            snap.runningProcessCount =
                am?.runningAppProcesses?.size ?: 0

            try {
                val usm =
                    context.getSystemService(Context.USAGE_STATS_SERVICE)
                        as UsageStatsManager

                val end = System.currentTimeMillis()
                val start = end - 10 * 60 * 1000L

                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    start,
                    end
                )

                val top = stats.maxByOrNull { it.lastTimeUsed }
                if (top != null) {
                    snap.foregroundApp = top.packageName
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "Process info gagal: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeviceMonitor"
    }
}

