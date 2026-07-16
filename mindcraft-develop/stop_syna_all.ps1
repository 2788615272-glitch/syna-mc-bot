# Stops all Syna / Mindcraft processes that may be holding listening ports.
# Safe by design: only kills processes that either
#   a) are LISTENING on one of the Mindcraft / Syna dedicated ports, or
#   b) have a command line that explicitly contains one of our entry-point scripts.
# It will NOT touch unrelated node/python processes (e.g. VS Code, browsers).

$ErrorActionPreference = 'SilentlyContinue'
$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null

# 8081 = mindcraft mindserver, 8766 = syna voice server.
# Do NOT add 8765 here - it is commonly used by VTube Studio / other java apps.
$ports = @(8081, 8766)
$cmdMarkers = @(
    'mindcraft-develop\main.js',
    'mindcraft-develop/main.js',
    'src\core\pure_mod_core.js',
    'src/core/pure_mod_core.js',
    'syna_voice_server.py',
    'syna_asr_server.py'
)

$killed = New-Object System.Collections.Generic.HashSet[int]

function Stop-Pid($targetPid, $reason) {
    if ($null -eq $targetPid) { return }
    if ($targetPid -le 4) { return }                          # don't touch System / Idle
    if ($targetPid -eq $PID) { return }                        # don't kill self
    if ($killed.Contains([int]$targetPid)) { return }
    try {
        $proc = Get-Process -Id $targetPid -ErrorAction Stop
        Stop-Process -Id $targetPid -Force -ErrorAction Stop
        [void]$killed.Add([int]$targetPid)
        Write-Host ("[stop_syna] killed PID {0} ({1}) - {2}" -f $targetPid, $proc.ProcessName, $reason) -ForegroundColor Yellow
    } catch {
        # process gone or access denied; ignore quietly
    }
}

# 1) Port-based cleanup: anything LISTENING on our dedicated ports.
foreach ($p in $ports) {
    try {
        $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction Stop
        foreach ($c in $conns) {
            Stop-Pid $c.OwningProcess ("port {0} listener" -f $p)
        }
    } catch {
        # port not in use - good
    }
}

# 2) CommandLine-based cleanup: catch orphans that aren't bound to a port yet.
try {
    $procs = Get-CimInstance Win32_Process -ErrorAction Stop |
             Where-Object { $_.Name -in @('node.exe', 'python.exe', 'pythonw.exe', 'python3.exe', 'python3.13.exe') }
    foreach ($p in $procs) {
        $cl = [string]$p.CommandLine
        if ([string]::IsNullOrWhiteSpace($cl)) { continue }
        foreach ($m in $cmdMarkers) {
            if ($cl -like "*$m*") {
                Stop-Pid $p.ProcessId ("cmdline match: $m")
                break
            }
        }
    }
} catch {
    # WMI not available - skip silently
}

if ($killed.Count -eq 0) {
    Write-Host "[stop_syna] nothing to clean up - all ports free." -ForegroundColor Green
} else {
    Write-Host ("[stop_syna] cleaned {0} process(es)." -f $killed.Count) -ForegroundColor Green
    Start-Sleep -Milliseconds 400   # give Windows a moment to release the sockets
}
