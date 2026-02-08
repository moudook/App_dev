@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d C:\Users\gbust\Smarty
call .\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
