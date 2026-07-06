$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$env:NODE_OPTIONS = "--enable-source-maps"

Set-Location $PSScriptRoot

Write-Host "[Syna] Starting three PowerShell windows: SynaVoice TTS + Mindcraft + SynaASR mic." -ForegroundColor Green
Write-Host "[Syna] If voice is silent, check VOLC_APP_ID / VOLC_ACCESS_TOKEN / VOLC_VOICE_ID." -ForegroundColor Yellow
Write-Host "[Syna] Tip: you can also use .\launch_syna_menu.ps1 for a simple menu launcher." -ForegroundColor Cyan

Start-Process powershell -ArgumentList @(
    '-NoExit',
    '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'run_syna_voice_server_cn.ps1')
)

Start-Sleep -Seconds 2

Start-Process powershell -ArgumentList @(
    '-NoExit',
    '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'run_syna_cn.ps1')
)

Start-Sleep -Seconds 3

Start-Process powershell -ArgumentList @(
    '-NoExit',
    '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'run_syna_asr_cn.ps1')
)

Write-Host "[Syna] Launch requested. Check the three new PowerShell windows:" -ForegroundColor Green
Write-Host "  1) SynaVoice TTS server (text-to-speech)" -ForegroundColor White
Write-Host "  2) Mindcraft main (bot logic)" -ForegroundColor White
Write-Host "  3) SynaASR mic (speech-to-text, auto-send to bot)" -ForegroundColor White
