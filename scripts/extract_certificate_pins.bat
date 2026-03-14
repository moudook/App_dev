@echo off
REM =============================================================================
REM CERTIFICATE PIN EXTRACTION SCRIPT (Windows)
REM =============================================================================
REM 
REM PURPOSE: Extract SHA-256 certificate pins for production domains
REM 
REM USAGE: extract_certificate_pins.bat
REM 
REM REQUIREMENTS:
REM - OpenSSL for Windows installed
REM - OpenSSL in PATH or OPENSSL_HOME set
REM - Internet connection
REM 
REM =============================================================================

setlocal enabledelayedexpansion

echo =============================================
echo CERTIFICATE PIN EXTRACTION TOOL (Windows)
echo =============================================
echo.

REM Check if OpenSSL is available
where openssl >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: OpenSSL not found in PATH
    echo.
    echo Please install OpenSSL for Windows:
    echo https://slproweb.com/products/Win32OpenSSL.html
    echo.
    echo Or set OPENSSL_HOME environment variable
    exit /b 1
)

echo OpenSSL found: 
where openssl
echo.

REM Function to extract certificate pin
:extract_pin
set DOMAIN=%1
set PORT=%2
if "%PORT%"=="" set PORT=443

echo Extracting certificate pin for: %DOMAIN%:%PORT%
echo -------------------------------------------

REM Create temp files
set TEMP_CERT=%TEMP%\cert_%RANDOM%.pem
set TEMP_PUBKEY=%TEMP%\pubkey_%RANDOM%.der

REM Connect and extract certificate
echo | openssl s_client -connect %DOMAIN%:%PORT% -servername %DOMAIN% 2>nul | openssl x509 -pubkey -noout > %TEMP_CERT% 2>nul

if not exist %TEMP_CERT% (
    echo ERROR: Could not extract certificate for %DOMAIN%
    echo.
    goto :eof
)

REM Extract public key and compute SHA-256 hash
openssl pkey -pubin -in %TEMP_CERT% -outform der 2>nul | openssl dgst -sha256 -binary | openssl enc -base64 > %TEMP_PUBKEY%

set /p PIN=<%TEMP_PUBKEY%

REM Clean up temp files
del %TEMP_CERT% %TEMP_PUBKEY% 2>nul

if "%PIN%"=="" (
    echo ERROR: Could not compute pin for %DOMAIN%
    echo.
    goto :eof
)

echo SUCCESS: Certificate pin extracted
echo.
echo Add this to HttpClientProvider.kt:
echo   .add("%DOMAIN%", "sha256/%PIN%")
echo.
echo =============================================
echo.

goto :eof

REM =============================================================================
REM PRODUCTION DOMAINS
REM =============================================================================

echo Extracting pins for production domains...
echo.

call :extract_pin "api.openai.com" 443
call :extract_pin "api.anthropic.com" 443
call :extract_pin "generativelanguage.googleapis.com" 443
call :extract_pin "huggingface.co" 443
call :extract_pin "api.tavily.com" 443

echo.
echo =============================================
echo ALL PINS EXTRACTED SUCCESSFULLY!
echo =============================================
echo.
echo NEXT STEPS:
echo 1. Copy the pins above to HttpClientProvider.kt
echo 2. Add BOTH current pin and backup pin
echo 3. Test in staging environment first
echo 4. Deploy to production
echo.
echo IMPORTANT: Keep backup pins for 90 days after certificate rotation!
echo.

endlocal
