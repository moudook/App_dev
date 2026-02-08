@echo off
echo Setting up USB Connection for Smarty App...
echo.
echo Running: adb reverse tcp:7860 tcp:7860
echo.
adb reverse tcp:7860 tcp:7860
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] ADB command failed.
    echo 1. Make sure your phone is connected via USB.
    echo 2. Make sure USB Debugging is ENABLED on your phone.
    echo 3. Make sure you have ADB installed and in your PATH.
    pause
    exit /b
)
echo.
echo [SUCCESS] Connection established!
echo.
echo The Android App can now connect to the Smarty Server at http://127.0.0.1:7860
echo.
echo Configuration in the App:
echo 1. In App Settings > Smarty Server Connection
echo 2. Set IP/URL to: 127.0.0.1
echo 3. Set Port to: 7860
echo 4. Set Use HTTPS to: OFF
echo 5. Click 'Test & Save'
echo.
pause
