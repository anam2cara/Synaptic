package com.synaptic.ai.core.model

class DeviceSnapshot {
    var ramFreeBytes: Long = 0
    var ramTotalBytes: Long = 0
    var ramUsedPercent: Float = 0f
    var cpuUsagePercent: Float = 0f
    var cpuPerCoreUsage: List<Float> = emptyList()
    var cpuTempCelsius: Float = 0f
    var batteryLevel: Int = 0
    var batteryTempCelsius: Float = 0f
    var isCharging: Boolean = false
    var storageFreeBytes: Long = 0
    var storageTotalBytes: Long = 0
    var uptimeMs: Long = 0

    var runningProcessCount: Int = 0
    var foregroundApp: String = "unknown"

    var gpuModel: String = "unknown"
    var gpuBusyPercent: Float = -1f
    var gpuTemperatureCelsius: Float = -1f
    var gpuFaults: String = "unknown"
    var gpuFaultProcesses: String = "unknown"
    var gpuResetCount: Long = -1L

            fun toReadableString(): String {
        return String.format(
            "RAM: %.1f%% terpakai (%.1f/%.1f GB)\n" +
            "CPU Load: %s | Suhu: %s\n" +
                "Baterai: %d%% | Suhu: %.1f°C%s\n" +
                "Storage: %.1f GB tersisa dari %.1f GB\n" +
                "GPU: %s | Busy: %s | Suhu: %s\n" +
                "GPU Faults: %s\n" +
                "GPU Fault Procs: %s\n" +
                "GPU Reset Count: %d",
            ramUsedPercent,
            (ramTotalBytes - ramFreeBytes) / 1e9f,
            ramTotalBytes / 1e9f,
            formatPercent(cpuUsagePercent),
            formatTemp(cpuTempCelsius),
            batteryLevel,
            batteryTempCelsius,
            if (isCharging) " (charging)" else "",
            storageFreeBytes / 1e9f,
            storageTotalBytes / 1e9f,
            gpuModel,
            formatPercent(gpuBusyPercent),
            formatTemp(gpuTemperatureCelsius),
            gpuFaults,
            gpuFaultProcesses,
            gpuResetCount
        )
    }

    fun toBatteryString(): String {
        return String.format(
            "Baterai: %d%%%s | Suhu baterai: %.1f°C",
            batteryLevel,
            if (isCharging) " (sedang mengisi daya)" else " (tidak mengisi daya)",
            batteryTempCelsius
        )
    }

    fun toRamString(): String {
        return String.format(
            "RAM: %.1f%% terpakai (%.1f/%.1f GB)",
            ramUsedPercent,
            (ramTotalBytes - ramFreeBytes) / 1e9f,
            ramTotalBytes / 1e9f
        )
    }

    fun toStorageString(): String {
        return String.format(
            "Storage: %.1f GB tersisa dari %.1f GB",
            storageFreeBytes / 1e9f,
            storageTotalBytes / 1e9f
        )
    }

    fun toThermalString(): String {
        return String.format(
            "Suhu CPU: %s | Suhu GPU: %s",
            formatTemp(cpuTempCelsius),
            formatTemp(gpuTemperatureCelsius)
        )
    }

    private fun formatPercent(value: Float): String =
        if (value >= 0f) String.format("%.1f%%", value) else "tidak tersedia"

    private fun formatTemp(value: Float): String =
        if (value > 0f && value < 100f) String.format("%.1f°C", value) else "tidak tersedia"
}





