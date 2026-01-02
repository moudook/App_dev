@echo off
setlocal EnableDelayedExpansion
title Local LLM Server - SECURE (HTTPS)
color 0B

echo ============================================================
echo   LOCAL LLM SERVER - ENCRYPTED (HTTPS) MODE
echo ============================================================
echo.
echo   This script runs llama-server with Caddy reverse proxy
echo   for automatic HTTPS encryption between your phone and PC.
echo.
echo ============================================================
echo.

:: Configuration
set SERVER_PORT=8000
set HTTPS_PORT=8443

:: Check if Caddy exists
if not exist "caddy_windows_amd64.exe" (
    echo [ERROR] caddy_windows_amd64.exe not found!
    echo.
    echo Please download Caddy:
    echo   1. Go to: https://caddyserver.com/download
    echo   2. Select: Windows amd64
    echo   3. Download and keep the filename as caddy_windows_amd64.exe
    echo.
    pause
    exit /b 1
)

echo [INFO] Detecting network interfaces...
echo.

:: Detect IPs
set DETECTED_IP=
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4 Address"') do (
    set "ip=%%a"
    set "ip=!ip: =!"
    if not "!ip!"=="127.0.0.1" (
        if "!DETECTED_IP!"=="" set DETECTED_IP=!ip!
        echo   Found: !ip!
    )
)
echo.

if "!DETECTED_IP!"=="" (
    echo [ERROR] No network interface found!
    pause
    exit /b 1
)

echo ============================================================
echo   SECURE CONNECTION INFO
echo ============================================================
echo.
echo   Your HTTPS URL: https://!DETECTED_IP!:!HTTPS_PORT!
echo.
echo   In your Android app:
echo   1. Settings -^> AI Providers -^> Local PC
echo   2. Enable "Use HTTPS"
echo   3. IP Address: !DETECTED_IP!
echo   4. Port: !HTTPS_PORT!
echo.
echo   NOTE: First connection may show certificate warning.
echo   This is normal for self-signed certificates.
echo ============================================================
echo.

:: Create Caddyfile for HTTPS
echo [INFO] Creating Caddy configuration...
(
echo :!HTTPS_PORT! {
echo     reverse_proxy 127.0.0.1:!SERVER_PORT!
echo     tls internal
echo }
) > Caddyfile

:: Add firewall rules
echo [INFO] Checking firewall rules...
netsh advfirewall firewall show rule name="Local LLM HTTPS" >nul 2>&1
if !errorLevel! neq 0 (
    netsh advfirewall firewall add rule name="Local LLM HTTPS" dir=in action=allow protocol=TCP localport=!HTTPS_PORT! >nul 2>&1
    echo [OK] Firewall rule added for HTTPS port !HTTPS_PORT!
) else (
    echo [OK] Firewall rule already exists
)

:: Check model
if not exist "models\qwen2.5-3b-instruct-q4_k_m.gguf" (
    echo [ERROR] Model file not found!
    pause
    exit /b 1
)

echo.
echo [INFO] Starting services...
echo [INFO] - LLM Server on localhost:!SERVER_PORT! (internal)
echo [INFO] - Caddy HTTPS proxy on *:!HTTPS_PORT! (external)
echo.
echo ============================================================
echo   SERVERS STARTING... PRESS CTRL+C TO STOP BOTH
echo ============================================================
echo.

:: Start llama-server in background (localhost only for security)
start /B "LLM Server" cmd /c ".\llama-server.exe --model models\qwen2.5-3b-instruct-q4_k_m.gguf --ctx-size 32768 --n-gpu-layers 20 --port !SERVER_PORT! --host 127.0.0.1 --parallel 2 --cont-batching"

:: Wait for server to start
timeout /t 3 /nobreak >nul

:: Start Caddy (foreground)
.\caddy_windows_amd64.exe run

:: Cleanup when Caddy exits
echo.
echo [INFO] Shutting down...
taskkill /F /IM llama-server.exe >nul 2>&1
del Caddyfile >nul 2>&1
echo [INFO] Servers stopped.
endlocal
pause
