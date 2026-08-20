package com.synaptic.ai.diagnostic

import com.synaptic.ai.core.model.DeviceSnapshot

class PerformanceAnalyzer {

    enum class Severity {
        NORMAL,
        WARNING,
        CRITICAL
    }

    data class AnalysisResult(
        val severity: Severity,
        val summary: String,
        val recommendations: List<String>
    )

    fun analyze(snapshot: DeviceSnapshot): AnalysisResult {
        val issues = mutableListOf<String>()
        val fixes = mutableListOf<String>()

        var severity = Severity.NORMAL

        if (snapshot.ramUsedPercent >= 90f) {
            severity = Severity.CRITICAL
            issues += "RAM sangat tinggi (${snapshot.ramUsedPercent.toInt()}%)"
            fixes += "Periksa proses/aplikasi dengan penggunaan RAM tinggi"
        } else if (snapshot.ramUsedPercent >= 75f) {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "RAM tinggi (${snapshot.ramUsedPercent.toInt()}%)"
        }

        if (snapshot.cpuUsagePercent >= 80f) {
            severity = maxSeverity(severity, Severity.CRITICAL)
            issues += "CPU utilization tinggi (${snapshot.cpuUsagePercent.toInt()}%)"
            fixes += "Periksa proses yang membebani CPU"
        } else if (snapshot.cpuUsagePercent >= 60f) {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "CPU utilization meningkat (${snapshot.cpuUsagePercent.toInt()}%)"
        }

        if (snapshot.gpuBusyPercent >= 80f) {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "GPU busy tinggi (${snapshot.gpuBusyPercent.toInt()}%)"
            fixes += "Periksa workload GPU"
        }

        if (snapshot.batteryLevel in 0..10 && !snapshot.isCharging) {
            severity = maxSeverity(severity, Severity.CRITICAL)
            issues += "Baterai kritis (${snapshot.batteryLevel}%)"
            fixes += "Hubungkan perangkat ke charger"
        }

        if (snapshot.batteryTempCelsius >= 45f) {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "Suhu baterai tinggi (${snapshot.batteryTempCelsius}°C)"
            fixes += "Kurangi beban perangkat dan pantau suhu"
        }

        if (snapshot.gpuFaults != "unknown" &&
            snapshot.gpuFaults != "0 0 0 0 0 0 0 0") {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "Terdapat GPU fault counter: ${snapshot.gpuFaults}"
        }

        if (snapshot.gpuFaultProcesses != "unknown" &&
            snapshot.gpuFaultProcesses != "0") {
            severity = maxSeverity(severity, Severity.WARNING)
            issues += "Terdapat data proses GPU fault: ${snapshot.gpuFaultProcesses}"
        }

        if (issues.isEmpty()) {
            return AnalysisResult(
                Severity.NORMAL,
                "Tidak ditemukan anomali berdasarkan telemetry yang tersedia.",
                emptyList()
            )
        }

        return AnalysisResult(
            severity,
            issues.joinToString("; "),
            fixes.distinct()
        )
    }

    private fun maxSeverity(current: Severity, next: Severity): Severity =
        if (next.ordinal > current.ordinal) next else current
}
