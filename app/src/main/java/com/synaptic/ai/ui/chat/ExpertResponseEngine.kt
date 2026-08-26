package com.synaptic.ai.ui.chat

import com.synaptic.ai.core.model.DeviceSnapshot
import java.util.Locale

object ExpertResponseEngine {

    fun generateExpertResponse(userMessage: String, snap: DeviceSnapshot): String? {
        val text = userMessage.lowercase(Locale("id"))
        
        return when {
            text.contains("batre") || text.contains("baterai") || text.contains("battery") -> 
                buildBatteryResponse(snap)
            
            text.contains("ram") || text.contains("memori") -> 
                buildRamResponse(snap)
                
            text.contains("cpu") || text.contains("prosesor") -> 
                buildCpuResponse(snap)
                
            text.contains("suhu") || text.contains("panas") || text.contains("thermal") -> 
                buildThermalResponse(snap)
                
            text.contains("storage") || text.contains("penyimpanan") || text.contains("ruang") -> 
                buildStorageResponse(snap)
                
            text.contains("status") || text.contains("kondisi") || text.contains("kesehatan") -> 
                buildGeneralResponse(snap)
                
            else -> null
        }
    }

    private fun buildBatteryResponse(s: DeviceSnapshot): String {
        val state = if (s.isCharging) "sedang mengisi daya (charging)" else "tidak sedang mengisi daya"
        val health = when {
            s.batteryTempCelsius > 45 -> "⚠️ Suhu baterai agak panas (${s.batteryTempCelsius}°C). Hindari penggunaan berat."
            s.batteryLevel < 20 -> "⚠️ Baterai rendah (${s.batteryLevel}%). Disarankan segera mengisi daya."
            else -> "✅ Kondisi baterai dalam keadaan normal."
        }
        return """
            🔋 *Status Baterai Anda:*
            • Level: ${s.batteryLevel}%
            • Status: $state
            • Suhu: ${String.format("%.1f", s.batteryTempCelsius)}°C
            
            $health
        """.trimIndent()
    }

    private fun buildRamResponse(s: DeviceSnapshot): String {
        val usedGB = (s.ramTotalBytes - s.ramFreeBytes) / 1e9f
        val totalGB = s.ramTotalBytes / 1e9f
        val advice = if (s.ramUsedPercent > 85) 
            "⚠️ RAM sangat penuh! Tutup beberapa aplikasi latar belakang untuk menjaga performa."
            else "✅ Penggunaan RAM masih dalam batas aman."
            
        return """
            🧠 *Analisis Memori (RAM):*
            • Terpakai: ${String.format("%.1f%%", s.ramUsedPercent)} (${String.format("%.1f", usedGB)}/${String.format("%.1f", totalGB)} GB)
            • Tersedia: ${String.format("%.1f", s.ramFreeBytes / 1e9f)} GB
            
            $advice
        """.trimIndent()
    }

    private fun buildCpuResponse(s: DeviceSnapshot): String {
        return """
            ⚙️ *Kinerja Prosesor (CPU):*
            • Beban Kerja: ${String.format("%.1f%%", s.cpuUsagePercent)}
            • Suhu Core: ${String.format("%.1f", s.cpuTempCelsius)}°C
            • Core Aktif: ${Runtime.getRuntime().availableProcessors()}
            
            ${if (s.cpuUsagePercent > 70) "⚠️ CPU sedang bekerja cukup keras." else "✅ Kinerja CPU stabil."}
        """.trimIndent()
    }

    private fun buildThermalResponse(s: DeviceSnapshot): String {
        val cpuColor = if (s.cpuTempCelsius > 50) "🔥" else "🌡️"
        return """
            $cpuColor *Informasi Suhu Perangkat:*
            • Suhu CPU: ${String.format("%.1f", s.cpuTempCelsius)}°C
            • Suhu GPU: ${if (s.gpuTemperatureCelsius > 0) String.format("%.1f", s.gpuTemperatureCelsius) + "°C" else "Tidak tersedia"}
            • Suhu Baterai: ${String.format("%.1f", s.batteryTempCelsius)}°C
            
            ${if (s.cpuTempCelsius > 60) "🔴 PERINGATAN: Perangkat mengalami overheat! Istirahatkan sejenak." else "✅ Suhu masih dalam batas wajar."}
        """.trimIndent()
    }

    private fun buildStorageResponse(s: DeviceSnapshot): String {
        val freeGB = s.storageFreeBytes / 1e9f
        val totalGB = s.storageTotalBytes / 1e9f
        val usedPercent = ((totalGB - freeGB) / totalGB) * 100
        return """
            📁 *Ruang Penyimpanan:*
            • Sisa Ruang: ${String.format("%.1f", freeGB)} GB
            • Total Kapasitas: ${String.format("%.1f", totalGB)} GB
            • Terpakai: ${String.format("%.1f%%", usedPercent)}
            
            ${if (usedPercent > 90) "⚠️ Ruang penyimpanan hampir habis!" else "✅ Ruang penyimpanan masih luas."}
        """.trimIndent()
    }

    private fun buildGeneralResponse(s: DeviceSnapshot): String {
        return """
            📱 *Ringkasan Kondisi Perangkat:*
            • Baterai: ${s.batteryLevel}% (${if (s.isCharging) "Charging" else "Discharging"})
            • RAM: ${String.format("%.1f%%", s.ramUsedPercent)} terpakai
            • CPU: ${String.format("%.1f%%", s.cpuUsagePercent)} load
            • Suhu Utama: ${String.format("%.1f", s.cpuTempCelsius)}°C
            • Foreground: ${s.foregroundApp}
            
            *Kesimpulan:* ${if (s.cpuTempCelsius > 50 || s.ramUsedPercent > 90) "Sistem sedang terbebani." else "Sistem berjalan optimal."}
        """.trimIndent()
    }
}
