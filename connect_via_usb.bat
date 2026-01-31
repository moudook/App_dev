@echo off
echo Setting up USB Connection for Smarty App...
echo.
echo Running: adb reverse tcp:1234 tcp:1234
echo.
adb reverse tcp:1234 tcp:1234
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
echo on your Mobile App:
echo 1. Go to Settings > AI Intelligence > Local LLM Server
echo 2. Set IP Address to: 127.0.0.1
echo 3. Set Port to: 1234
echo 4. Click 'Test & Save'
echo.
pause
