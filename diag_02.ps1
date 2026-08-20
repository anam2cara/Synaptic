$path = "D:\Documents\projek_build_apk_saya\SynapticBuild\core\src\main\java\com\synaptic\ai\core\model\DeviceSnapshot.kt"
$raw = [System.IO.File]::ReadAllText($path)
$idx = $raw.IndexOf("Suhu baterai")
if ($idx -ge 0) {
    $snippet = $raw.Substring($idx, 25)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($snippet)
    Write-Host "Snippet: $snippet"
    Write-Host "Bytes: $([System.BitConverter]::ToString($bytes))"
} else {
    Write-Host "NOT FOUND"
}
