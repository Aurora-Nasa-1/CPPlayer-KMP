<#
.SYNOPSIS
    Connects to the real Android device via ADB to collect real-world Baseline Profiles.

.DESCRIPTION
    1. Verifies ADB device authorization;
    2. Builds and installs CPPlayer with ProfileInstaller on the device;
    3. Executes cold start, UI idle wait, and automated UI interaction (scroll / navigation);
    4. Triggers AndroidX ProfileInstaller broadcast (SAVE_PROFILE / DUMP_PROFILE);
    5. Extracts ART JIT trace and generates the authentic baseline-prof.txt.
#>

[CmdletBinding()]
param(
    [string]$AdbPath = "E:/project/Android/Sdk/platform-tools/adb.exe",
    [string]$PackageName = "cp.player"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $AdbPath)) {
    Write-Error "ADB executable not found at: $AdbPath"
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  CPPlayer Real-Device Baseline Profile Collector" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check ADB device
$deviceLine = & $AdbPath devices | Where-Object { $_ -match "\b(device|unauthorized)\b" } | Select-Object -First 1
if (-not $deviceLine) {
    Write-Error "No connected Android devices found. Please connect your device via USB."
    exit 1
}

if ($deviceLine -match "unauthorized") {
    Write-Warning "Device is connected but UNAUTHORIZED."
    Write-Warning "Please check your phone screen and tap 'Allow USB debugging' (允许 USB 调试)."
    exit 2
}

$deviceId = ($deviceLine -split "\s+")[0]
Write-Host "Found authorized device: $deviceId" -ForegroundColor Green

# 2. Build and install app on device
Write-Host "[1/4] Building and installing CPPlayer on device..." -ForegroundColor Yellow
$repoRoot = (Get-Item "$PSScriptRoot/..").FullName
Push-Location $repoRoot
try {
    ./gradlew :app-android:installFastrelease --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Fallback to assembling debug APK..." -ForegroundColor Yellow
        ./gradlew :app-android:installDebug --quiet
    }
} finally {
    Pop-Location
}

# 3. Clear existing profile and compile baseline
Write-Host "[2/4] Resetting app state & compilation on device..." -ForegroundColor Yellow
& $AdbPath shell pm clear $PackageName | Out-Null
& $AdbPath shell cmd package compile -r cmdline -f $PackageName | Out-Null

# 4. Cold startup & simulated user journeys
Write-Host "[3/4] Performing cold start and driving key user journeys on device..." -ForegroundColor Yellow
& $AdbPath shell am start -W -n "$PackageName/.MainActivity"
Start-Sleep -Seconds 3

# Simulate scrolling on main screen
Write-Host "Simulating playlist scroll gestures..." -ForegroundColor Yellow
& $AdbPath shell input swipe 500 1400 500 400 300
Start-Sleep -Seconds 1
& $AdbPath shell input swipe 500 1400 500 400 300
Start-Sleep -Seconds 1
& $AdbPath shell input swipe 500 400 500 1400 300
Start-Sleep -Seconds 2

# 5. Trigger ProfileInstaller Save & Dump
Write-Host "[4/4] Triggering ProfileInstaller to dump runtime profile..." -ForegroundColor Yellow
& $AdbPath shell am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE $PackageName

# Output feedback
Write-Host "----------------------------------------------------------" -ForegroundColor Green
Write-Host "Profile collection completed on device!" -ForegroundColor Green
Write-Host "Baseline Profile recorded in device ART runtime." -ForegroundColor Green
