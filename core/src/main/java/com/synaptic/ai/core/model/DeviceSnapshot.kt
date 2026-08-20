package com.synaptic.ai.core.model

class DeviceSnapshot {
    var ramFreeBytes: Long = 0
    var ramTotalBytes: Long = 0
    var ramUsedPercent: Float = 0f
    var cpuUsagePercent: Float = 0f
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
                "CPU Load: %.1f%% | Suhu: %.1f°C\n" +
                "Baterai: %d%% | Suhu: %.1f°C%s\n" +
                "Storage: %.1f GB tersisa dari %.1f GB\n" +
                "GPU: %s | Busy: %.1f%% | Suhu: %.1f°C\n" +
                "GPU Faults: %s\n" +
                "GPU Fault Procs: %s\n" +
                "GPU Reset Count: %d",
            ramUsedPercent,
            (ramTotalBytes - ramFreeBytes) / 1e9f,
            ramTotalBytes / 1e9f,
            cpuUsagePercent,
            cpuTempCelsius,
            batteryLevel,
            batteryTempCelsius,
            if (isCharging) " (charging)" else "",
            storageFreeBytes / 1e9f,
            storageTotalBytes / 1e9f,
            gpuModel,
            gpuBusyPercent,
            gpuTemperatureCelsius,
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
            "Suhu CPU: %.1f°C | Suhu GPU: %.1f°C",
            cpuTempCelsius,
            gpuTemperatureCelsius
        )
    }
}





