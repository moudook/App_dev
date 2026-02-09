@echo off
setlocal
title Smarty Server (Antigravity Mode)

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set ACTIVE_PROVIDER=ANTIGRAVITY
set ANTHROPIC_BASE_URL=http://localhost:8080/v1
set ANTHROPIC_API_KEY=dummy
set TAVILY_API_KEY=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
set DB_URL=jdbc:postgresql://127.0.0.1:5432/smarty_db
set DB_USER=smarty_user
set DB_PASSWORD=smarty_pass
set SERVER_PORT=7860

echo Starting Smarty Server (Antigravity Proxy Mode)...
echo Proxy URL: %ANTHROPIC_BASE_URL%

call .\gradlew.bat :server:run --no-daemon
pause
