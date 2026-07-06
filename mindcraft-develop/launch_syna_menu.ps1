$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null

$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$env:NODE_OPTIONS = "--enable-source-maps"

Set-Location $PSScriptRoot

function Show-Header {
    Clear-Host
    Write-Host "==============================================" -ForegroundColor DarkCyan
    Write-Host "         Syna / Mindcraft Launcher" -ForegroundColor Cyan
    Write-Host "==============================================" -ForegroundColor DarkCyan
    Write-Host "Workspace: $PSScriptRoot" -ForegroundColor DarkGray
    Write-Host ""
}

function Pause-Menu {
    Write-Host ""
    Read-Host "Press Enter to return"
}

function Start-InNewWindow([string]$scriptName, [string]$title) {
    $scriptPath = Join-Path $PSScriptRoot $scriptName
    if (-not (Test-Path $scriptPath)) {
        Write-Host "[Error] Missing script: $scriptPath" -ForegroundColor Red
        return
    }

    Write-Host "[Start] $title" -ForegroundColor Green
    Start-Process powershell -ArgumentList @(
        '-NoExit',
        '-ExecutionPolicy', 'Bypass',
        '-Command',
        "`$Host.UI.RawUI.WindowTitle = '$title'; & '$scriptPath'"
    )
}

function Get-JsonSafe([string]$path) {
    try {
        if (Test-Path $path) {
            return Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
        }
    }
    catch {
        Write-Host "[Warn] Failed to read config: $path" -ForegroundColor Yellow
    }
    return $null
}

function Show-ConfigSummary {
    Show-Header
    $keysPath = Join-Path $PSScriptRoot 'keys.json'
    $profilePath = Join-Path $PSScriptRoot 'profiles\kimi.json'
    $profile = Get-JsonSafe $profilePath
    $keys = Get-JsonSafe $keysPath

    Write-Host "[Config Summary]" -ForegroundColor Cyan
    Write-Host "keys.json : $keysPath"
    Write-Host "profile   : $profilePath"
    Write-Host ""

    if ($profile) {
        Write-Host "Agent     : $($profile.name)" -ForegroundColor Green
        Write-Host "Model     : $($profile.model.api) / $($profile.model.model)" -ForegroundColor Green
    }
    else {
        Write-Host "Agent     : unreadable" -ForegroundColor Yellow
    }

    if ($keys) {
        $hasVolc = [bool]($keys.VOLC_APP_ID) -and [bool]($keys.VOLC_ACCESS_TOKEN) -and [bool]($keys.VOLC_VOICE_ID)
        $ttsText = if ($hasVolc) { 'configured' } else { 'incomplete' }
        $ttsColor = if ($hasVolc) { 'Green' } else { 'Yellow' }
        Write-Host "TTS       : $ttsText" -ForegroundColor $ttsColor
        Write-Host "MC URL    : $($keys.MINDCRAFT_URL)"
        Write-Host "MC Agent  : $($keys.MINDCRAFT_AGENT)"
    }
    else {
        Write-Host "keys.json : unreadable or missing" -ForegroundColor Yellow
    }

    Pause-Menu
}

function Test-VoiceService {
    Show-Header
    $scriptPath = Join-Path $PSScriptRoot 'test_syna_voice.ps1'
    if (-not (Test-Path $scriptPath)) {
        Write-Host "[Error] Missing test script: $scriptPath" -ForegroundColor Red
        Pause-Menu
        return
    }

    Write-Host "[Run] Testing local Syna voice service" -ForegroundColor Cyan
    & $scriptPath
    Pause-Menu
}

function Send-TestMessage {
    Show-Header
    $keysPath = Join-Path $PSScriptRoot 'keys.json'
    $profilePath = Join-Path $PSScriptRoot 'profiles\kimi.json'
    $keys = Get-JsonSafe $keysPath
    $profile = Get-JsonSafe $profilePath

    $agentName = ''
    if ($keys -and $keys.MINDCRAFT_AGENT) {
        $agentName = [string]$keys.MINDCRAFT_AGENT
    }
    elseif ($profile -and $profile.name) {
        $agentName = [string]$profile.name
    }

    Write-Host "[Send Test Message]" -ForegroundColor Cyan
    Write-Host "Voice bridge URL: http://127.0.0.1:8766/send-text"
    Write-Host "Target agent    : $agentName"
    Write-Host ""

    $text = Read-Host "Enter text for syna (leave empty for default)"
    if (-not $text) {
        $text = "Hello syna, please introduce what you can do and come to me."
    }

    try {
        $payload = @{
            text = $text
            agent_name = $agentName
            from = 'User'
        } | ConvertTo-Json -Compress

        $res = Invoke-RestMethod -Uri 'http://127.0.0.1:8766/send-text' -Method Post -ContentType 'application/json; charset=utf-8' -Body $payload -TimeoutSec 6
        Write-Host "[OK] $($res | ConvertTo-Json -Compress)" -ForegroundColor Green
    }
    catch {
        Write-Host "[Fail] $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "Please ensure the voice bridge and Mindcraft are both running." -ForegroundColor Yellow
    }

    Pause-Menu
}

while ($true) {
    Show-Header
    Write-Host "1. Start Mindcraft main" -ForegroundColor White
    Write-Host "2. Start Syna voice service" -ForegroundColor White
    Write-Host "3. Start all" -ForegroundColor White
    Write-Host "4. Test voice service" -ForegroundColor White
    Write-Host "5. Send test message to syna" -ForegroundColor White
    Write-Host "6. Show config summary" -ForegroundColor White
    Write-Host "0. Exit" -ForegroundColor White
    Write-Host ""

    $choice = Read-Host "Select an option"

    switch ($choice) {
        '1' {
            Start-InNewWindow 'run_syna_cn.ps1' 'Syna - Mindcraft Main'
            Pause-Menu
        }
        '2' {
            Start-InNewWindow 'run_syna_voice_server_cn.ps1' 'Syna - Voice Server'
            Pause-Menu
        }
        '3' {
            Start-InNewWindow 'run_all_syna_cn.ps1' 'Syna - Launch All'
            Pause-Menu
        }
        '4' { Test-VoiceService }
        '5' { Send-TestMessage }
        '6' { Show-ConfigSummary }
        '0' {
            Write-Host "Bye~" -ForegroundColor Cyan
            break
        }
        default {
            Write-Host "[Hint] Invalid option." -ForegroundColor Yellow
            Start-Sleep -Milliseconds 900
        }
    }
}