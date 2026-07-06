# Stops only Syna ASR processes. This is intentionally narrower than stop_syna_all.ps1.
$ErrorActionPreference = 'SilentlyContinue'
$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null

$killed = New-Object System.Collections.Generic.HashSet[int]

function Stop-Pid($targetPid, $reason) {
    if ($null -eq $targetPid) { return }
    if ($targetPid -le 4) { return }
    if ($targetPid -eq $PID) { return }
    if ($killed.Contains([int]$targetPid)) { return }
    try {
        $proc = Get-Process -Id $targetPid -ErrorAction Stop
        Stop-Process -Id $targetPid -Force -ErrorAction Stop
        [void]$killed.Add([int]$targetPid)
        Write-Host ("[stop_syna_asr] killed PID {0} ({1}) - {2}" -f $targetPid, $proc.ProcessName, $reason) -ForegroundColor Yellow
    } catch {}
}

try {
    $procs = Get-CimInstance Win32_Process -ErrorAction Stop |
             Where-Object { $_.Name -in @('python.exe', 'pythonw.exe', 'python3.exe', 'python3.13.exe') }
    foreach ($p in $procs) {
        $cl = [string]$p.CommandLine
        if ([string]::IsNullOrWhiteSpace($cl)) { continue }
        if ($cl -like '*syna_asr_server.py*') {
            Stop-Pid $p.ProcessId 'cmdline match: syna_asr_server.py'
        }
    }
} catch {
    Write-Host '[stop_syna_asr] WMI process scan unavailable; skip command-line cleanup.' -ForegroundColor DarkYellow
}

if ($killed.Count -gt 0) {
    Start-Sleep -Milliseconds 500
    Write-Host ("[stop_syna_asr] cleaned {0} ASR process(es)." -f $killed.Count) -ForegroundColor Green
} else {
    Write-Host '[stop_syna_asr] no old ASR process found.' -ForegroundColor Green
}
