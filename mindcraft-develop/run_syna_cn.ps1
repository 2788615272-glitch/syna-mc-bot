$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$env:NODE_OPTIONS = "--enable-source-maps"

Set-Location $PSScriptRoot
& (Join-Path $PSScriptRoot 'stop_syna_all.ps1')
Write-Host "[Syna] UTF-8 environment set. Starting Mindcraft..." -ForegroundColor Green
Write-Host "[Syna] Tip: if LAN auto-scan misses your world, run with `$env:MINECRAFT_PORT='<局域网端口>' first." -ForegroundColor Yellow
node main.js
