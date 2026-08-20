$path = "D:\Documents\projek_build_apk_saya\SynapticBuild\tools\src\main\java\com\synaptic\ai\tools\ToolExecutor.kt"
$raw = [System.IO.File]::ReadAllText($path)
$c = $raw.Replace("`r`n", "`n")

$tests = [ordered]@{
    "signature" = 'private fun executeDeviceStatus(): ToolResult {'
    "buildString_line" = 'val interpretation = buildString {'
    "analisis_realtime" = 'appendLine("[Analisis Real-time]:")'
    "ram_kritis" = 'appendLine("- RAM Kritis! Segera periksa aplikasi/proses yang aktif.")'
    "ram_interp" = 'appendLine("- Penggunaan RAM Normal (${snap.ramUsedPercent.toInt()}%).")'
    "suhu_line" = '"- Suhu terukur: ${snap.cpuTempCelsius}'
    "derajat_c" = "`u{00B0}C;"
    "baterai_lemah" = 'appendLine("- Baterai sangat lemah, segera hubungkan ke charger.")'
    "makeresult_line" = 'makeResult(true, interpretation, "", 0)'
}

foreach ($key in $tests.Keys) {
    $needle = $tests[$key]
    $found = $c.Contains($needle)
    Write-Host "$key => $found"
}

Write-Host ""
Write-Host "--- raw bytes around 'Suhu terukur' ---"
$idx = $c.IndexOf("Suhu terukur")
if ($idx -ge 0) {
    $snippet = $c.Substring($idx, 60)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($snippet)
    Write-Host "Snippet: $snippet"
    Write-Host "Bytes: $([System.BitConverter]::ToString($bytes))"
} else {
    Write-Host "'Suhu terukur' not found at all"
}
