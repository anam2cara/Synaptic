# ============================================================
# Synaptic - Perbaikan #1: Query baterai/RAM/storage/suhu jadi
# spesifik per-metrik (tidak lagi dump laporan lengkap), dan
# hapus teks "[Analisis Real-time]" yang hardcoded/template.
# Files: DeviceSnapshot.kt, ToolExecutor.kt, ChatViewModel.kt
# ============================================================

$ErrorActionPreference = "Stop"

$snapshotPath  = "D:\Documents\projek_build_apk_saya\SynapticBuild\core\src\main\java\com\synaptic\ai\core\model\DeviceSnapshot.kt"
$executorPath  = "D:\Documents\projek_build_apk_saya\SynapticBuild\tools\src\main\java\com\synaptic\ai\tools\ToolExecutor.kt"
$viewModelPath = "D:\Documents\projek_build_apk_saya\SynapticBuild\app\src\main\java\com\synaptic\ai\ui\chat\ChatViewModel.kt"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Patch-File {
    param(
        [string]$Path,
        [string]$Old,
        [string]$New,
        [string]$Label
    )

    $rawContent = [System.IO.File]::ReadAllText($Path)
    $hadCrlf = $rawContent.Contains("`r`n")
    $content = $rawContent.Replace("`r`n", "`n")
    $normOld = $Old.Replace("`r`n", "`n")
    $normNew = $New.Replace("`r`n", "`n")

    $count = ([regex]::Matches($content, [regex]::Escape($normOld))).Count

    if ($count -ne 1) {
        Write-Host "[GAGAL] $Label - anchor ditemukan $count kali (harus 1) di $Path" -ForegroundColor Red
        exit 1
    }

    $backup = "$Path.bak_$(Get-Date -Format 'yyyyMMdd_HHmmss_fff')"
    Copy-Item -Path $Path -Destination $backup -Force
    Write-Host "[BACKUP] $backup"

    $newContent = $content.Replace($normOld, $normNew)
    if ($hadCrlf) {
        $newContent = $newContent.Replace("`n", "`r`n")
    }
    [System.IO.File]::WriteAllText($Path, $newContent, $utf8NoBom)
    Write-Host "[OK] $Label"
}

# --- 1. DeviceSnapshot.kt: tambah method scoped ---
$old1 = @'
            gpuResetCount
        )
    }
}
'@

$new1 = @'
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
'@

Patch-File -Path $snapshotPath -Old $old1 -New $new1 -Label "DeviceSnapshot.kt: tambah 4 method scoped"

# --- 2. ToolExecutor.kt: dispatch pass args ---
$old2 = '            "device_status" -> executeDeviceStatus()'
$new2 = '            "device_status" -> executeDeviceStatus(args)'
Patch-File -Path $executorPath -Old $old2 -New $new2 -Label "ToolExecutor.kt: dispatch device_status terima args"

# --- 3. ToolExecutor.kt: ganti executeDeviceStatus jadi scoped, hapus template Analisis Real-time ---
$old3 = @'
    private fun executeDeviceStatus(): ToolResult {
        return try {
            val snap = deviceMonitor.getSnapshot()

            val interpretation = buildString {
                appendLine(snap.toReadableString())
                appendLine()
                appendLine("[Analisis Real-time]:")

                when {
                    snap.ramUsedPercent > 90 ->
                        appendLine("- RAM Kritis! Segera periksa aplikasi/proses yang aktif.")

                    snap.ramUsedPercent > 75 ->
                        appendLine("- Penggunaan RAM cukup tinggi.")

                    else ->
                        appendLine("- Penggunaan RAM Normal (${snap.ramUsedPercent.toInt()}%).")
                }

                appendLine(
                    "- Suhu terukur: ${snap.cpuTempCelsius}°C; " +
                    "sensor thermal belum diidentifikasi secara spesifik."
                )

                if (snap.batteryLevel < 15 && !snap.isCharging) {
                    appendLine("- Baterai sangat lemah, segera hubungkan ke charger.")
                }
            }

            makeResult(true, interpretation, "", 0)

        } catch (e: Exception) {
            makeResult(
                false,
                "Gagal baca status device: ${e.message}",
                e.stackTraceToString(),
                1
            )
        }
    }
'@

$new3 = @'
    private fun executeDeviceStatus(args: String): ToolResult {
        return try {
            val snap = deviceMonitor.getSnapshot()
            val scope = extractJsonString(args, "scope").lowercase()

            val output = when (scope) {
                "battery" -> snap.toBatteryString()
                "ram" -> snap.toRamString()
                "storage" -> snap.toStorageString()
                "thermal" -> snap.toThermalString()
                else -> snap.toReadableString()
            }

            makeResult(true, output, "", 0)

        } catch (e: Exception) {
            makeResult(
                false,
                "Gagal baca status device: ${e.message}",
                e.stackTraceToString(),
                1
            )
        }
    }
'@

Patch-File -Path $executorPath -Old $old3 -New $new3 -Label "ToolExecutor.kt: executeDeviceStatus scoped, hapus template Analisis Real-time"

# --- 4. ChatViewModel.kt: tambah resolver scope ---
$old4 = '    private fun shouldAttachDeviceContext(userMessage: String): Boolean {'
$new4 = @'
    private fun resolveDeviceStatusScope(userMessage: String): String {
        val text = userMessage.lowercase(Locale("id"))
        return when {
            text.contains("batre") || text.contains("baterai") || text.contains("battery") -> "battery"
            text.contains("ram") -> "ram"
            text.contains("storage") || text.contains("penyimpanan") -> "storage"
            text.contains("suhu") -> "thermal"
            else -> "all"
        }
    }

    private fun shouldAttachDeviceContext(userMessage: String): Boolean {
'@

Patch-File -Path $viewModelPath -Old $old4 -New $new4 -Label "ChatViewModel.kt: tambah resolveDeviceStatusScope"

# --- 5. ChatViewModel.kt: executeToolDirect kirim scope sebagai args ---
$old5 = '            val result = toolExecutor.execute(toolName, "{}")'
$new5 = @'
            val directArgs = if (toolName == "device_status") {
                JSONObject().put("scope", resolveDeviceStatusScope(userMessage)).toString()
            } else {
                "{}"
            }

            val result = toolExecutor.execute(toolName, directArgs)
'@

Patch-File -Path $viewModelPath -Old $old5 -New $new5 -Label "ChatViewModel.kt: executeToolDirect kirim scope"

# --- 6. ChatViewModel.kt: log actionlog pakai args asli ---
$old6 = '            val log = ActionLog("$toolName: {}", result.output, result.isSuccess, currentId)'
$new6 = '            val log = ActionLog("$toolName: $directArgs", result.output, result.isSuccess, currentId)'
Patch-File -Path $viewModelPath -Old $old6 -New $new6 -Label "ChatViewModel.kt: ActionLog pakai args asli"

Write-Host ""
Write-Host "=== SELESAI: Perbaikan #1 (scoped battery/RAM/storage/suhu) berhasil diterapkan ==="
Write-Host "Verifikasi manual:"
Write-Host "1. Build: .\gradlew.bat assembleDebug"
Write-Host "2. Install: adb install -r app\build\outputs\apk\debug\app-debug.apk"
Write-Host "3. Test: tanya 'cek sisa baterai' -> harus cuma muncul baris Baterai, bukan laporan lengkap"
