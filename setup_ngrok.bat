@echo off
setlocal
title Smarty - ngrok Setup

echo ===================================================
echo   Smarty - Internet Access Setup (ngrok)
echo ===================================================
echo.
echo This script helps you set up ngrok so you can access
echo your local server from anywhere in the world.
echo.

:: Check if ngrok is already installed
where ngrok >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [OK] ngrok is already installed and in your PATH.
    echo.
) else (
    echo [MISSING] ngrok is not found.
    echo.
    echo Please download it from: https://ngrok.com/download
    echo.
    echo Once downloaded, unzip it and place 'ngrok.exe' in this folder:
    echo %CD%
    echo.
    pause

    if exist ngrok.exe (
        echo [OK] Found ngrok.exe in current folder.
    ) else (
        echo [ERROR] Still could not find ngrok.exe. Exiting.
        pause
        exit /b 1
    )
)

echo.
echo [Action Required] Authentication
echo ---------------------------------------------------
echo To use ngrok, you need a free Authtoken.
echo 1. Log in to https://dashboard.ngrok.com/get-started/your-authtoken
echo 2. Copy your Authtoken (starts with 1z...)
echo.
set /p AUTH_TOKEN="Paste your Authtoken here: "

if "%AUTH_TOKEN%"=="" (
    echo [Skipped] No token provided.
) else (
    echo.
    echo Running: ngrok config add-authtoken [HIDDEN]
    ngrok config add-authtoken %AUTH_TOKEN%
    echo.
    echo [Success] ngrok is configured!
)

echo.
echo ===================================================
echo   Setup Complete!
echo ===================================================
echo.
echo You can now use:
echo 1. 'run_server_public.bat' -> To host Smarty Server (Port 7860)
echo 2. 'ngrok http 8083'       -> To host your Custom Proxy (Port 8083)
echo.
pause
