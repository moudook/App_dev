@echo off
setlocal

:: Set JAVA_HOME to Android Studio's JBR (Java 17)
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%" (
    echo Warning: Android Studio JBR not found at %JAVA_HOME%
    echo Please update JAVA_HOME in this script to point to a Java 17+ installation.
)

:: Database Configuration
set DB_URL=jdbc:postgresql://localhost:5432/smarty_db
set DB_USER=smarty_user
set DB_PASSWORD=smarty_pass
set SERVER_PORT=7860

:: Load API Keys from .env if it exists
if exist .env (
    for /f "tokens=*" %%a in (.env) do set %%a
)

:: Check for required keys
if "%OPENAI_API_KEY%"=="" (
    echo Warning: OPENAI_API_KEY is not set.
    set /p OPENAI_API_KEY="Enter OPENAI_API_KEY: "
)

if "%TAVILY_API_KEY%"=="" (
    echo Warning: TAVILY_API_KEY is not set.
    set /p TAVILY_API_KEY="Enter TAVILY_API_KEY (optional): "
)

echo Starting Smarty Server...
echo Database: %DB_URL%
echo Port: %SERVER_PORT%

call .\gradlew.bat :server:run --no-daemon
endlocal
