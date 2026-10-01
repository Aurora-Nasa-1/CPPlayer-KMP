param(
    [string]$Version = "0.0.0-fastrelease",
    # Overriding versionCode here is deliberate - the only allowed exception.
    # The default 900001 sits below the derived 1_000_000 for 1.0.0, so a real
    # release can always replace a local build, while installing this on top of
    # a release is permitted by the "-d" flag below. Every other path lets Gradle
    # derive the code from versionName - see the root build.gradle.kts.
    [int]$VersionCode = 900001,
    [switch]$Clean,
    [switch]$Launch
)

$ErrorActionPreference = "Stop"

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $File $($Arguments -join ' ')" }
}

$gradleArgs = @(":app-android:assembleFastrelease", "--no-daemon", "--project-prop", "app.versionName=$Version", "--project-prop", "app.versionCode=$VersionCode", "--project-prop", "app.releaseChannel=fastrelease")
if ($Clean) { $gradleArgs = @("clean") + $gradleArgs }
Write-Host "Building fastrelease APK..." -ForegroundColor Cyan
Invoke-Checked ".\gradlew.bat" $gradleArgs

$apk = Join-Path $PSScriptRoot "..\app-android\build\outputs\apk\fastrelease\app-android-fastrelease.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adb) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($sdkRoot) { $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"; if (Test-Path $candidate) { $adb = Get-Item $candidate } }
}
if ($null -eq $adb) { throw "adb not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH." }

Invoke-Checked $adb.Source @("start-server")
$devices = (& $adb.Source devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) { throw "No authorized Android device found. Enable USB debugging and accept the RSA prompt." }

Write-Host "Installing $apk" -ForegroundColor Cyan
Invoke-Checked $adb.Source @("install", "-r", "-d", $apk)
if ($Launch) {
    Invoke-Checked $adb.Source @("shell", "monkey", "-p", "cp.player", "1")
}
Write-Host "fastrelease installed successfully." -ForegroundColor Green
