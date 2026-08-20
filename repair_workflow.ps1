# Synaptic Project Repair & Cleanup Workflow
# This script automates file cleanup and minor UI patches.

$ErrorActionPreference = "Continue"

Write-Host "--- Starting Synaptic Project Cleanup ---" -ForegroundColor Cyan

# 1. Cleanup Junk Files
Write-Host "[1/3] Cleaning up backup and dump files..." -ForegroundColor Yellow
$filesToDelete = @(
    "dump*.xml",
    "pkgdump.txt",
    "diagnostic_pulled.log",
    "screen1.png",
    "screen1_small.jpg",
    "synaptic_audit_*"
)

foreach ($pattern in $filesToDelete) {
    Remove-Item -Path "$PSScriptRoot\$pattern" -Force -ErrorAction SilentlyContinue
}

# Recursive cleanup of .bak files
Get-ChildItem -Path $PSScriptRoot -Filter "*.bak_*" -Recurse | Remove-Item -Force

Write-Host "Cleanup completed." -ForegroundColor Green

# 2. Patching UI TODOs (Safe replacement)
Write-Host "[2/3] Patching UI TODOs with placeholder actions..." -ForegroundColor Yellow

function Patch-File {
    param($FilePath, $Search, $Replace)
    if (Test-Path $FilePath) {
        (Get-Content $FilePath) -replace [regex]::Escape($Search), $Replace | Set-Content $FilePath
        Write-Host "Patched: $(Split-Path $FilePath -Leaf)" -ForegroundColor Gray
    }
}

# ChatScreen.kt - Plus action placeholder
Patch-File `
    -FilePath "$PSScriptRoot\app\src\main/java/com/synaptic/ai/ui/chat/ChatScreen.kt" `
    -Search "onClick = { /* TODO: Plus action */ }" `
    -Replace 'onClick = { android.util.Log.d("SynapticUI", "Plus action clicked"); android.widget.Toast.makeText(context, "Fitur Lampiran akan segera hadir", android.widget.Toast.LENGTH_SHORT).show() }'

# ShellScreen.kt - Suggestion chip placeholder
Patch-File `
    -FilePath "$PSScriptRoot\app\src\main/java/com/synaptic/ai/ui/shell/ShellScreen.kt" `
    -Search "modifier = Modifier.clickable { /* TODO */ }" `
    -Replace 'modifier = Modifier.clickable { android.util.Log.d("SynapticShell", "Suggestion clicked: $text") }'

# ToolsScreen.kt - ToolCard button placeholder
Patch-File `
    -FilePath "$PSScriptRoot\app\src\main/java/com/synaptic/ai/ui/tools/ToolsScreen.kt" `
    -Search "onClick = { /* TODO */ }" `
    -Replace 'onClick = { android.util.Log.d("SynapticTools", "Tool action: $title") }'

Write-Host "Patching completed." -ForegroundColor Green

# 3. Final Build Check
Write-Host "[3/3] Suggestion: Run './gradlew.bat :app:assembleDebug' to verify changes." -ForegroundColor Cyan
Write-Host "--- Workflow Finished Successfully ---" -ForegroundColor Cyan
