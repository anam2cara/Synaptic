$targetFile = "D:\Documents\projek_build_apk_saya\Synaptic\app\src\main\java\com\synaptic\ai\ui\chat\ChatViewModel.kt"
$backupDir  = "D:\Documents\projek_build_apk_saya\Synaptic\_patches"
$timestamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$backupFile = Join-Path $backupDir "ChatViewModel.kt.bak_$timestamp"

if (-not (Test-Path $targetFile)) {
    Write-Host "GAGAL: File target tidak ditemukan: $targetFile"
} else {
    if (-not (Test-Path $backupDir)) {
        New-Item -ItemType Directory -Path $backupDir | Out-Null
    }

    Copy-Item -Path $targetFile -Destination $backupFile
    Write-Host "Backup dibuat: $backupFile"

    $raw = Get-Content -Path $targetFile -Raw

    $pattern = '(?m)^(?<indent>[ \t]*)if\s*\(\s*prefs\.isConfirmBeforeExec\s*&&\s*iteration\s*==\s*0\s*\)\s*\{\s*$'
    $regex = [System.Text.RegularExpressions.Regex]::new($pattern)
    $foundMatches = $regex.Matches($raw)

    Write-Host "Jumlah baris yang cocok dengan pattern: $($foundMatches.Count)"

    if ($foundMatches.Count -ne 1) {
        Write-Host "GAGAL: Pattern tidak ditemukan tepat 1 kali. Tidak ada perubahan dilakukan."
        Write-Host "File asli TIDAK diubah. Backup tetap ada di: $backupFile"
    } else {
        $evaluator = {
            param($m)
            $indent = $m.Groups["indent"].Value
            return $indent + "if (com.synaptic.ai.tools.ToolRegistry.get(toolName)?.requiresConfirmation == true || (prefs.isConfirmBeforeExec && iteration == 0)) {"
        }

        $newRaw = $regex.Replace($raw, [System.Text.RegularExpressions.MatchEvaluator]$evaluator)

        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($targetFile, $newRaw, $utf8NoBom)

        Write-Host "PATCH BERHASIL DITULIS."
        Write-Host ""
        Write-Host "=== Safety-check: isi file setelah patch ==="
        $patchedLines = Get-Content -Path $targetFile
        $lineIndex = -1
        for ($i = 0; $i -lt $patchedLines.Count; $i++) {
            if ($patchedLines[$i] -match 'ToolRegistry\.get\(toolName\)\?\.requiresConfirmation') {
                $lineIndex = $i
                break
            }
        }
        if ($lineIndex -ge 0) {
            $start = [Math]::Max(0, $lineIndex - 5)
            $end   = [Math]::Min($patchedLines.Count - 1, $lineIndex + 10)
            for ($i = $start; $i -le $end; $i++) {
                Write-Host "$($i+1): $($patchedLines[$i])"
            }
        } else {
            Write-Host "PERINGATAN: Baris hasil patch tidak ditemukan saat safety-check. Periksa manual."
        }
    }
}
