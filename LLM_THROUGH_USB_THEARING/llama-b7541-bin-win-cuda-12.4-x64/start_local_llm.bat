@echo off
setlocal EnableDelayedExpansion
title Local LLM Server (USB + WiFi)
color 0A

echo ============================================================
echo   LOCAL LLM SERVER - USB TETHERING + WIFI SUPPORT
echo ============================================================
echo.

:: Check if running as admin (needed for firewall rule)
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [WARNING] Not running as Administrator
    echo [WARNING] Firewall rules may not be added automatically
    echo.
)

:: Configuration - Change port here if needed
set SERVER_PORT=8000

echo [INFO] Detecting ALL network interfaces...
echo.
echo ============================================================
echo   ALL AVAILABLE IP ADDRESSES
echo ============================================================

:: Show ALL IPv4 addresses for the user to choose
echo.
echo Your PC has these IP addresses:
echo.
for /f "tokens=1,2,* delims=:" %%a in ('ipconfig ^| findstr /i "IPv4 Address"') do (
    set "ip=%%b"
    set "ip=!ip: =!"
    echo   - !ip!
)
echo.

:: Try to identify each type
set USB_IP=
set WIFI_IP=
set OTHER_IP=

:: Get all IPs and categorize them
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4 Address"') do (
    set "ip=%%a"
    set "ip=!ip: =!"

    :: Skip localhost
    if "!ip!"=="127.0.0.1" (
        REM skip
    ) else if "!ip:~0,3!"=="10." (
        if "!USB_IP!"=="" set USB_IP=!ip!
    ) else if "!ip:~0,8!"=="192.168." (
        if "!WIFI_IP!"=="" set WIFI_IP=!ip!
    ) else if "!ip:~0,4!"=="172." (
        if "!OTHER_IP!"=="" set OTHER_IP=!ip!
    ) else (
        if "!OTHER_IP!"=="" set OTHER_IP=!ip!
    )
)

echo ============================================================
echo   DETECTED CONNECTION TYPES
echo ============================================================
echo.

set HAS_ANY_IP=0

if not "!USB_IP!"=="" (
    echo   [USB TETHERING]
    echo   IP Address: !USB_IP!
    echo   URL: http://!USB_IP!:!SERVER_PORT!
    echo.
    set HAS_ANY_IP=1
)

if not "!WIFI_IP!"=="" (
    echo   [WIFI - 192.168.x.x]
    echo   IP Address: !WIFI_IP!
    echo   URL: http://!WIFI_IP!:!SERVER_PORT!
    echo.
    set HAS_ANY_IP=1
)

if not "!OTHER_IP!"=="" (
    echo   [OTHER NETWORK]
    echo   IP Address: !OTHER_IP!
    echo   URL: http://!OTHER_IP!:!SERVER_PORT!
    echo.
    set HAS_ANY_IP=1
)

if "!HAS_ANY_IP!"=="0" (
    echo [ERROR] No network interfaces detected!
    echo.
    echo Please ensure:
    echo   - USB tethering is enabled on your phone, OR
    echo   - WiFi is connected, OR
    echo   - Ethernet cable is connected
    echo.
    echo Run 'ipconfig' to see all available network adapters.
    pause
    exit /b 1
)

echo ============================================================
echo   HOW TO CONNECT FROM YOUR ANDROID APP
echo ============================================================
echo.
echo   1. Go to Settings -^> AI Providers
echo   2. Find "Local PC" section
echo   3. Enter one of the IP addresses shown above
echo   4. Set port to: !SERVER_PORT!
echo.
echo   IMPORTANT: Your phone must be able to reach your PC:
echo   - For USB: Phone connected via USB cable with tethering ON
echo   - For WiFi: BOTH devices on the SAME WiFi network
echo ============================================================
echo.

:: Add firewall rule (will fail silently if not admin or already exists)
echo [INFO] Ensuring firewall rules exist for port !SERVER_PORT!...

netsh advfirewall firewall show rule name="Local LLM Server" >nul 2>&1
if !errorLevel! neq 0 (
    netsh advfirewall firewall add rule name="Local LLM Server" dir=in action=allow protocol=TCP localport=!SERVER_PORT! >nul 2>&1
    if !errorLevel! equ 0 (
        echo [OK] Firewall rule added for port !SERVER_PORT!
    ) else (
        echo [WARNING] Could not add firewall rule. Run as Administrator if connection fails.
    )
) else (
    echo [OK] Firewall rule already exists
)
echo.

:: Check if model exists
if not exist "models\qwen2.5-3b-instruct-q4_k_m.gguf" (
    echo [ERROR] Model file not found!
    echo [ERROR] Expected: models\qwen2.5-3b-instruct-q4_k_m.gguf
    echo.
    echo Download a GGUF model and place it in the 'models' folder.
    pause
    exit /b 1
)

echo [INFO] Starting LLM server...
echo [INFO] Model: qwen2.5-3b-instruct-q4_k_m.gguf
echo [INFO] Context: 32768 tokens
echo [INFO] GPU Layers: 20 (RTX 2050)
echo [INFO] Port: !SERVER_PORT!
echo [INFO] Host: 0.0.0.0 (accepting connections from any device)
echo [INFO] Parallel Slots: 2 (can process 2 requests simultaneously)
echo [INFO] Continuous Batching: ENABLED
echo.
echo ============================================================
echo   SERVER STARTING... PRESS CTRL+C TO STOP
echo ============================================================
echo.

.\llama-server.exe ^
  --model models\qwen2.5-3b-instruct-q4_k_m.gguf ^
  --ctx-size 32768 ^
  --n-gpu-layers 20 ^
  --port !SERVER_PORT! ^
  --host 0.0.0.0 ^
  --parallel 2 ^
  --cont-batching ^
  --verbose-prompt

echo.
echo [INFO] Server stopped.
endlocal
pause
