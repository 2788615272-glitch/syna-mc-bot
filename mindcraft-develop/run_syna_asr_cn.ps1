$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

Set-Location $PSScriptRoot
# ASR is a client (mic -> http POST), no listening port. Skip auto-cleanup
# to avoid killing the freshly-started Mindcraft / voice server.
Write-Host "[SynaASR] Starting microphone ASR service..." -ForegroundColor Cyan
Write-Host "[SynaASR] Speak into your mic -> auto-send to Mindcraft bot" -ForegroundColor Yellow
Write-Host ""
Write-Host "[SynaASR] Cleaning old ASR process first..." -ForegroundColor DarkYellow
& (Join-Path $PSScriptRoot 'stop_syna_asr.ps1')

python services/syna_asr_server.py --all-input-devices --always-listen --rms-threshold 90 --max-recording-seconds 8

