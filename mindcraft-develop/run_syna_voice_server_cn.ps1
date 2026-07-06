$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

Set-Location $PSScriptRoot
& (Join-Path $PSScriptRoot 'stop_syna_all.ps1')
Write-Host "[SynaVoice] UTF-8 environment set. Starting LAN voice server..." -ForegroundColor Green
Write-Host "[SynaVoice] Requires VOLC_APP_ID / VOLC_ACCESS_TOKEN / VOLC_VOICE_ID for Volc TTS." -ForegroundColor Yellow
python .\services\syna_voice_server.py --host 0.0.0.0
