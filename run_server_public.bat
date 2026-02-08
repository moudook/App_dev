@echo off
setlocal
title Smarty Public Access

echo ===================================================
echo   Smarty Server - Public Internet Access Setup
echo ===================================================

:: 1. Check for ngrok
where ngrok >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] ngrok is not found in your PATH.
    echo.
    echo Please follow these steps:
    echo 1. Download ngrok from https://ngrok.com/download
    echo 2. Extract it and add the folder to your System PATH
    echo    (or place ngrok.exe in this folder: %CD%)
    echo 3. Run 'ngrok config add-authtoken <your-token>'
    echo.
    pause
    exit /b 1
)

:: 2. Start the Local Server
echo.
echo [1/2] Starting Smarty Local Server (Port 7860)...
echo       This will open in a new window. Do not close it.
start "Smarty Local Server" cmd /k "run_local_server.bat"

:: 3. Start ngrok Tunnel
echo.
echo [2/2] Starting ngrok secure tunnel...
echo.
echo INSTRUCTIONS FOR APP:
echo ---------------------------------------------------
echo 1. Look for the "Forwarding" URL below
echo    (e.g., https://a1b2-c3d4.ngrok-free.app)
echo.
echo 2. Open Smarty App -> Settings -> Remote Server
echo 3. Enter the following:
echo    - IP: a1b2-c3d4.ngrok-free.app  (The domain part)
echo    - Port: 443
echo    - Use HTTPS: ON
echo.
echo Press any key to start the tunnel...
pause >nul

ngrok http 7860
