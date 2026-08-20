package com.synaptic.ai.diagnostic

import com.synaptic.ai.core.model.DeviceSnapshot

object DiagnosticContextBuilder {

    fun build(snapshot: DeviceSnapshot): String {
        return """
[DEVICE_DIAGNOSTICS]

RAM_USED_PERCENT=${snapshot.ramUsedPercent}

CPU_LOAD_NORMALIZED_PERCENT=${snapshot.cpuUsagePercent}
NOTE_CPU=Normalized load, bukan CPU utilization langsung.

CPU_TEMPERATURE_C=${snapshot.cpuTempCelsius}

BATTERY_LEVEL_PERCENT=${snapshot.batteryLevel}
BATTERY_TEMPERATURE_C=${snapshot.batteryTempCelsius}
IS_CHARGING=${snapshot.isCharging}

STORAGE_FREE_BYTES=${snapshot.storageFreeBytes}
STORAGE_TOTAL_BYTES=${snapshot.storageTotalBytes}

GPU_MODEL=${snapshot.gpuModel}
GPU_BUSY_PERCENT=${snapshot.gpuBusyPercent}
GPU_TEMPERATURE_C=${snapshot.gpuTemperatureCelsius}
GPU_FAULTS_RAW=${snapshot.gpuFaults}
GPU_FAULT_PROCESSES_RAW=${snapshot.gpuFaultProcesses}
GPU_RESET_COUNT_RAW=${snapshot.gpuResetCount}

RUNNING_PROCESS_COUNT=${snapshot.runningProcessCount}
FOREGROUND_APP=${snapshot.foregroundApp}

UPTIME_MS=${snapshot.uptimeMs}

RULE:
- Jangan menyatakan GPU fault/reset sebagai kejadian baru hanya berdasarkan counter kumulatif.
- Jangan menyatakan overheat hanya berdasarkan angka suhu tanpa mengetahui sensor/thermal zone.
- Jangan mengubah CPU load menjadi klaim CPU utilization.
- Bedakan DATA, INTERPRETASI, dan KETIDAKPASTIAN.
""".trimIndent()
    }
}
