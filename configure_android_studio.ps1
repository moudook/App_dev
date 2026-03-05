# Android Studio Auto-Configuration Script for Smarty
# This script automatically configures Android Studio to use Java 17

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Android Studio Java 17 Auto-Config" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Java 17 installation path
$JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"

# Check if Java 17 exists
if (-not (Test-Path $JAVA_HOME)) {
    Write-Host "ERROR: Java 17 not found at $JAVA_HOME" -ForegroundColor Red
    Write-Host "Please install Java 17 first from:" -ForegroundColor Yellow
    Write-Host "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.msi" -ForegroundColor Yellow
    pause
    exit 1
}

Write-Host "[OK] Java 17 found at $JAVA_HOME" -ForegroundColor Green
Write-Host ""

# Set JAVA_HOME for current session
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"

# Set JAVA_HOME permanently (user level)
try {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $JAVA_HOME, "User")
    Write-Host "[OK] JAVA_HOME set permanently (user level)" -ForegroundColor Green
} catch {
    Write-Host "[WARN] Could not set JAVA_HOME permanently: $_" -ForegroundColor Yellow
    Write-Host "You may need to run as Administrator" -ForegroundColor Yellow
}

# Add to PATH permanently (user level)
try {
    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$JAVA_HOME\bin*") {
        [Environment]::SetEnvironmentVariable("Path", "$JAVA_HOME\bin;$currentPath", "User")
        Write-Host "[OK] Java 17 added to PATH permanently" -ForegroundColor Green
    } else {
        Write-Host "[OK] Java 17 already in PATH" -ForegroundColor Green
    }
} catch {
    Write-Host "[WARN] Could not update PATH: $_" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Finding Android Studio..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Find Android Studio installations
$androidStudioPaths = @(
    "$env:LOCALAPPDATA\Google\AndroidStudio2025.2.3",
    "$env:LOCALAPPDATA\Google\AndroidStudio2025.2.2",
    "$env:LOCALAPPDATA\Google\AndroidStudio2025.1.2",
    "$env:PROGRAMFILES\Android\Android Studio"
)

$foundAS = $false
$asPath = ""

foreach ($path in $androidStudioPaths) {
    if (Test-Path $path) {
        $foundAS = $true
        $asPath = $path
        Write-Host "[OK] Found Android Studio at: $path" -ForegroundColor Green
        break
    }
}

if (-not $foundAS) {
    Write-Host "[WARN] Android Studio not found in standard locations" -ForegroundColor Yellow
    Write-Host "Please manually configure Gradle JDK in Android Studio:" -ForegroundColor Yellow
    Write-Host "  File > Settings > Build > Gradle > Gradle JDK" -ForegroundColor Yellow
    pause
    exit 0
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Configuring Android Studio..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Create Android Studio configuration directory
$asConfigDir = "$env:APPDATA\Google\AndroidStudio2025.2.3"
if (-not (Test-Path $asConfigDir)) {
    $asConfigDir = "$env:APPDATA\Google\AndroidStudio2025.2.2"
}
if (-not (Test-Path $asConfigDir)) {
    $asConfigDir = "$env:APPDATA\Google\AndroidStudio2025.1.2"
}

if (Test-Path $asConfigDir) {
    Write-Host "[OK] Android Studio config found at: $asConfigDir" -ForegroundColor Green
    
    # Create options directory
    $optionsDir = Join-Path $asConfigDir "options"
    if (-not (Test-Path $optionsDir)) {
        New-Item -ItemType Directory -Path $optionsDir -Force | Out-Null
        Write-Host "[OK] Created options directory" -ForegroundColor Green
    }
    
    # Create gradle.xml configuration
    $gradleXmlPath = Join-Path $optionsDir "gradle.xml"
    $gradleXmlContent = @"
<application>
  <component name="GradleLocalSettings">
    <option name="serviceExecutionPath" value="" />
    <option name="gradleJvm" value="17" />
    <option name="gradleHome" value="" />
  </component>
</application>
"@
    
    try {
        Set-Content -Path $gradleXmlPath -Value $gradleXmlContent -Encoding UTF8
        Write-Host "[OK] Created gradle.xml with Java 17 configuration" -ForegroundColor Green
    } catch {
        Write-Host "[WARN] Could not create gradle.xml: $_" -ForegroundColor Yellow
    }
    
    # Create jdk.table.xml configuration
    $jdkTableXmlPath = Join-Path $optionsDir "jdk.table.xml"
    $jdkTableXmlContent = @"
<application>
  <component name="ProjectJdkTable">
    <jdk version="2">
      <name value="Microsoft 17.0.18" />
      <type value="JavaSDK" />
      <version value="version 17.0.18" />
      <homePath value="$JAVA_HOME" />
      <roots>
        <annotationsPath>
          <root type="composite">
            <root url="jar://`$APPLICATION_HOME_DIR`/plugins/java/lib/resources/jdkAnnotations.jar!/" type="simple" />
          </root>
        </annotationsPath>
        <classPath>
          <root type="composite">
            <root url="jrt://$JAVA_HOME!/java.base" type="simple" />
          </root>
        </classPath>
        <sourcePath>
          <root type="composite" />
        </sourcePath>
      </roots>
      <additional />
    </jdk>
  </component>
</application>
"@
    
    try {
        Set-Content -Path $jdkTableXmlPath -Value $jdkTableXmlContent -Encoding UTF8
        Write-Host "[OK] Created jdk.table.xml with Java 17 JDK" -ForegroundColor Green
    } catch {
        Write-Host "[WARN] Could not create jdk.table.xml: $_" -ForegroundColor Yellow
    }
} else {
    Write-Host "[WARN] Android Studio config directory not found" -ForegroundColor Yellow
    Write-Host "Please open Android Studio once to create configuration" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Verifying Java Configuration..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Verify Java version
Write-Host "Checking Java version..." -ForegroundColor Cyan
try {
    $javaVersion = & "$JAVA_HOME\bin\java.exe" -version 2>&1
    Write-Host "[OK] Java version:" -ForegroundColor Green
    Write-Host "  $javaVersion" -ForegroundColor White
} catch {
    Write-Host "[ERROR] Could not verify Java version: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Configuration Complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next Steps:" -ForegroundColor Yellow
Write-Host "1. Close this window" -ForegroundColor White
Write-Host "2. Restart Android Studio (if open)" -ForegroundColor White
Write-Host "3. Open your Smarty project" -ForegroundColor White
Write-Host "4. Go to: File > Settings > Build > Gradle" -ForegroundColor White
Write-Host "5. Verify 'Gradle JDK' shows 'Microsoft 17.0.18'" -ForegroundColor White
Write-Host "6. Click Build > Rebuild Project" -ForegroundColor White
Write-Host ""
Write-Host "If you see any issues, run:" -ForegroundColor Yellow
Write-Host "  File > Invalidate Caches > Invalidate and Restart" -ForegroundColor White
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan

pause
