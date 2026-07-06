@echo off
chcp 65001 >nul
title Syna 语音交流 MC Bot - 一键启动
echo ============================================================
echo   Syna 语音交流 MC Bot - 一键启动
echo ============================================================
echo.
echo 将依次启动：
echo   1. Syna TTS 语音服务 (端口 8766)
echo   2. Syna ASR 麦克风识别 (火山 bigmodel_async)
echo   3. Mindcraft MC Bot (端口 8081)
echo.
echo 按 Ctrl+C 可终止所有服务
echo ============================================================
echo.

cd /d "%~dp0"

:: 启动 TTS 语音服务
echo [1/3] 启动 TTS 语音服务...
start "Syna-TTS" cmd /c "python services/syna_voice_server.py 2>&1"
timeout /t 2 /nobreak >nul

:: 启动 ASR 麦克风识别
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop_syna_asr.ps1"
timeout /t 1 /nobreak >nul

echo [2/3] 启动 ASR 麦克风识别...
start "Syna-ASR" cmd /c "python services/syna_asr_server.py --sender-name SynaMic --all-input-devices --always-listen --rms-threshold 90 --max-recording-seconds 8 2>&1"
timeout /t 2 /nobreak >nul

:: 启动 Mindcraft
echo [3/3] 启动 Mindcraft MC Bot...
echo.
node main.js

pause
