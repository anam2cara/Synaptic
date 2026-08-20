package com.synaptic.ai.monitor

import android.content.Context

/**
 * Helper class untuk grab real-time device data untuk embedding ke LLM prompt.
 * Data disimpan sebagai string yang siap di-inject ke system prompt.
 */
class DeviceDataProvider(context: Context) {
    
    private val deviceMonitor = DeviceMonitor(context)
    
    /**
     * Get device status snapshot sebagai formatted string untuk embedding ke prompt.
     * Data real-time siap pakai di LLM tanpa perlu tool call.
     */
    fun getDeviceStatusSnapshot(): String {
        return try {
            val snap = deviceMonitor.getSnapshot()
            val ramUsedGB = (snap.ramTotalBytes - snap.ramFreeBytes) / 1e9f
            val ramTotalGB = snap.ramTotalBytes / 1e9f
            val storageUsedGB = (snap.storageTotalBytes - snap.storageFreeBytes) / 1e9f
            val storageTotalGB = snap.storageTotalBytes / 1e9f
            
            buildString {
                append("[STATUS PERANGKAT REAL-TIME]\n")
                append("• RAM: ${String.format("%.1f%%", snap.ramUsedPercent)} terpakai (${"%.1f".format(ramUsedGB)}/${"%.1f".format(ramTotalGB)} GB)\n")
                append("• CPU: ${String.format("%.1f%%", snap.cpuUsagePercent)}\n")
                append("• Baterai: ${snap.batteryLevel}%${if (snap.isCharging) " (charging)" else ""}\n")
                append("• Storage: ${"%.1f".format(storageTotalGB - storageUsedGB)} GB tersisa dari ${"%.1f".format(storageTotalGB)} GB (${String.format("%.0f%%", (storageUsedGB / storageTotalGB) * 100f)} terpakai)\n")
                append("• Proses berjalan: ${snap.runningProcessCount}\n")
                append("• App foreground: ${snap.foregroundApp}\n")
            }
        } catch (e: Exception) {
            "[Status perangkat tidak tersedia]"
        }
    }
    
    /**
     * Get performance analysis sebagai formatted string.
     * Berguna untuk recommendation dalam response.
     */
    fun getPerformanceAnalysis(): String {
        return try {
            val snap = deviceMonitor.getSnapshot()
            buildString {
                append("[ANALISIS PERFORMA]\n")
                when {
                    snap.ramUsedPercent > 80 -> append("⚠️ RAM tinggi (>80%), pertimbangkan tutup aplikasi yang tidak perlu\n")
                    snap.ramUsedPercent > 60 -> append("• RAM sedang (60-80%), performa masih baik\n")
                    else -> append("✅ RAM baik (<60%), performa optimal\n")
                }
                when {
                    snap.cpuUsagePercent > 70 -> append("⚠️ CPU tinggi (>70%), ada proses berat berjalan\n")
                    else -> append("✅ CPU normal\n")
                }
                when {
                    snap.batteryLevel < 20 -> append("🔋 Baterai rendah (<20%), charging segera\n")
                    snap.batteryLevel < 50 && !snap.isCharging -> append("🔋 Baterai medium, recommended charging\n")
                    else -> append("🔋 Baterai baik\n")
                }
            }
        } catch (e: Exception) {
            "[Analisis performa tidak tersedia]"
        }
    }
    
    /**
     * Get combined device context untuk embedding ke LLM prompt.
     * Ini akan di-inject ke system prompt jadi model punya konteks real-time.
     */
    fun getCombinedDeviceContext(): String {
        return buildString {
            append(getDeviceStatusSnapshot())
            append("\n")
            append(getPerformanceAnalysis())
        }
    }
}
