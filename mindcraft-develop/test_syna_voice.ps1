[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null

$baseUrl = "http://127.0.0.1:8766"
$text = "Syna voice service test message."
$agentName = ""

Write-Host "[SynaVoiceTest] Checking $baseUrl/health ..." -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/health" -Method Get -TimeoutSec 2
    Write-Host "[SynaVoiceTest] Service online: $($health | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "[SynaVoiceTest] Connection failed. Start .\run_syna_voice_server_cn.ps1 first." -ForegroundColor Red
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor DarkYellow
    exit 1
}

Write-Host "[SynaVoiceTest] Sending test speech: $text" -ForegroundColor Cyan
try {
    $body = @{ text = $text; interrupt = $true } | ConvertTo-Json -Compress
    $res = Invoke-RestMethod -Uri "$baseUrl/say" -Method Post -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 3
    Write-Host "[SynaVoiceTest] Queued: $($res | ConvertTo-Json -Compress)" -ForegroundColor Green
} catch {
    Write-Host "[SynaVoiceTest] Speech request failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "[SynaVoiceTest] Optional text injection test: POST $baseUrl/send-text" -ForegroundColor Cyan
Write-Host "[SynaVoiceTest] Set `$agentName in this script or configure MINDCRAFT_AGENT in keys.json before using it." -ForegroundColor DarkYellow
if ($agentName) {
    try {
        $body = @{ text = "你好，我想先试试你现在会做什么。"; agent_name = $agentName; from = "User" } | ConvertTo-Json -Compress
        $res = Invoke-RestMethod -Uri "$baseUrl/send-text" -Method Post -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 5
        Write-Host "[SynaVoiceTest] Forwarded: $($res | ConvertTo-Json -Compress)" -ForegroundColor Green
    } catch {
        Write-Host "[SynaVoiceTest] Forward request failed: $($_.Exception.Message)" -ForegroundColor Red
    }
}
