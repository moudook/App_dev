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
set HOTSPOT_IP=
set WIFI_IP=

:: Get all IPs and categorize them
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4 Address"') do (
    set "ip=%%a"
    set "ip=!ip: =!"

    :: Check for standard Windows Hotspot IP (192.168.137.1)
    if "!ip!"=="192.168.137.1" (
        set HOTSPOT_IP=!ip!
    ) else if "!ip:~0,3!"=="10." (
        if "!USB_IP!"=="" set USB_IP=!ip!
    ) else if "!ip:~0,8!"=="192.168." (
        if "!WIFI_IP!"=="" set WIFI_IP=!ip!
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

if not "!HOTSPOT_IP!"=="" (
    echo   [WINDOWS HOTSPOT - RECOMMENDED]
    echo   IP Address: !HOTSPOT_IP!
    echo   URL: http://!HOTSPOT_IP!:!SERVER_PORT!
    echo.
    set HAS_ANY_IP=1
) else (
    if not "!WIFI_IP!"=="" (
        echo   [WIFI / HOTSPOT]
        echo   IP Address: !WIFI_IP!
        echo   URL: http://!WIFI_IP!:!SERVER_PORT!
        echo.
        set HAS_ANY_IP=1
    )
)

if "!HAS_ANY_IP!"=="0" (
    echo [ERROR] No network detected.
    echo Please turn on Mobile Hotspot or Connect USB.
    pause
    exit /b 1
)

echo ============================================================
echo   HOW TO CONNECT (WIRELESS HOTSPOT METHOD)
echo ============================================================
echo.
echo   1. On this Laptop: Open Settings -^> Network -^> Mobile Hotspot
echo   2. Turn Mobile Hotspot: ON
echo   3. Connect your Phone's WiFi to this Laptop's Hotspot
echo.
echo   4. In App, use the IP Address shown above.
echo      (Usually 192.168.137.1 or similar)
echo.
echo   5. Set Port to: !SERVER_PORT!
echo   6. Turn HTTPS: OFF
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
:: Check if model exists
if not exist "models\chatglm3-6b-128k.Q8_0.gguf" (
    echo [ERROR] Model file not found!
    echo [ERROR] Expected: models\chatglm3-6b-128k.Q8_0.gguf
    echo.
    echo Download a GGUF model and place it in the 'models' folder.
    pause
    exit /b 1
)

echo [INFO] Starting LLM server...
echo [INFO] Model: chatglm3-6b-128k.Q8_0.gguf
echo [INFO] Context: 131072 tokens
echo [INFO] GPU Layers: 35 (Optimized)
echo [INFO] Port: !SERVER_PORT!
echo [INFO] Host: 0.0.0.0 (accepting connections from any device)
echo [INFO] Parallel Slots: 2 (can process 2 requests simultaneously)
echo [INFO] Continuous Batching: ENABLED
echo [INFO] Flash Attention: AUTO
echo.
echo ============================================================
echo   SERVER STARTING... PRESS CTRL+C TO STOP
echo ============================================================
echo.

.\llama-server.exe ^
  --model models\chatglm3-6b-128k.Q8_0.gguf ^
  --ctx-size 131072 ^
  --n-gpu-layers 35 ^
  --port !SERVER_PORT! ^
  --host 0.0.0.0 ^
  --parallel 2 ^
  --cont-batching ^
  --flash-attn auto ^
  --verbose-prompt

echo.
echo [INFO] Server stopped.
endlocal
pause
