@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

:menu
cls
echo ==============================================
echo          Syna / Mindcraft Launcher
echo ==============================================
echo 1. Start Mindcraft main
echo 2. Start Syna voice service
echo 3. Start all
echo 4. Test voice service
echo 5. Send test message to syna
echo 6. Show quick help
echo 9. Stop all Syna processes (free ports 8081/8766)
echo 0. Exit
echo.
set /p choice=Select an option: 

if "%choice%"=="1" goto start_main
if "%choice%"=="2" goto start_voice
if "%choice%"=="3" goto start_all
if "%choice%"=="4" goto test_voice
if "%choice%"=="5" goto send_msg
if "%choice%"=="6" goto help
if "%choice%"=="9" goto stop_all
if "%choice%"=="0" goto end
goto menu

:start_main
call :clean_ports
start "Syna - Mindcraft Main" powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0run_syna_cn.ps1"
goto wait_back

:start_voice
call :clean_ports
start "Syna - Voice Server" powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0run_syna_voice_server_cn.ps1"
goto wait_back

:start_all
call :clean_ports
start "Syna - Launch All" powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0run_all_syna_cn.ps1"
goto wait_back

:stop_all
call :clean_ports
goto wait_back

:clean_ports
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop_syna_all.ps1"
exit /b

:test_voice
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0test_syna_voice.ps1"
goto wait_back

:send_msg
set "msg="
set /p msg=Enter text for syna (empty = default): 
if "%msg%"=="" set "msg=Hello syna, please introduce what you can do and come to me."
set "SYNA_MENU_MSG=%msg%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$payload = @{ text = $env:SYNA_MENU_MSG; from = 'User' } | ConvertTo-Json -Compress; Invoke-RestMethod -Uri 'http://127.0.0.1:8766/send-text' -Method Post -ContentType 'application/json; charset=utf-8' -Body $payload"
set "SYNA_MENU_MSG="
goto wait_back

:help
echo.
echo Recommended order:
echo 1^) Start all
echo 2^) Test voice service
echo 3^) Send a test message to syna
echo.
echo If send message fails, make sure Mindcraft and the voice bridge are already running.
goto wait_back

:wait_back
echo.
pause
goto menu

:end
endlocal