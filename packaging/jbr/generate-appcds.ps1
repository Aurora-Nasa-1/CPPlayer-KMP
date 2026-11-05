<#
.SYNOPSIS
    Generates an AppCDS (Application Class Data Sharing) archive for CPPlayer Desktop.

.DESCRIPTION
    AppCDS dumps pre-verified and resolved class metadata (Compose Desktop, Skiko,
    Kotlin stdlib, Coroutines, and app classes) into a single read-only .jsa memory archive.
    On future launches, the JVM mmap's this archive, slashing class-loading time
    and boosting cold startup by 40%~60%.
#>

[CmdletBinding()]
param(
    [string]$OutputFile = "$PSScriptRoot/app-cds.jsa"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Get-Item "$PSScriptRoot\..\..").FullName
$outputPath = [System.IO.Path]::GetFullPath($OutputFile)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  CPPlayer AppCDS Archive Generator" -ForegroundColor Cyan
Write-Host "  Destination: $outputPath" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Ensure project is built
Write-Host "[1/3] Assembling desktop distribution image for class gathering..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    ./gradlew :app:createDistributable -x test --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to assemble distributable image"
    }

    $appImageDir = Join-Path $repoRoot "app/build/compose/binaries/main/app/CPPlayer"
    $javaExe = Join-Path $appImageDir "runtime/bin/java.exe"
    if (-not (Test-Path $javaExe)) {
        # Fallback to in-repo .jbr
        $javaExe = Join-Path $repoRoot ".jbr/windows-x64/bin/java.exe"
    }

    if (-not (Test-Path $javaExe)) {
        throw "Could not locate java executable in app-image or .jbr"
    }

    Write-Host "[2/3] Performing dynamic class training run (3-second warmup)..." -ForegroundColor Yellow
    if (Test-Path $outputPath) {
        Remove-Item $outputPath -Force
    }

    $appDir = Join-Path $appImageDir "app"
    $mainJar = Get-ChildItem -Path $appDir -Filter "*.jar" | Where-Object { $_.Name -like "app-*.jar" -or $_.Name -like "CPPlayer*.jar" } | Select-Object -First 1

    # Dynamic CDS Archive Dump
    $cdsArgs = @(
        "-XX:ArchiveClassesAtExit=$outputPath",
        "-Dcp.player.cdsDump=true",
        "-Dcp.player.releaseChannel=debug",
        "-cp", "$appDir/*",
        "cp.player.app.MainKt"
    )

    $process = Start-Process -FilePath $javaExe -ArgumentList $cdsArgs -PassThru -NoNewWindow
    Start-Sleep -Seconds 4
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }

    Write-Host "[3/3] Verifying generated AppCDS archive..." -ForegroundColor Yellow
    if (Test-Path $outputPath) {
        $fileSize = (Get-Item $outputPath).Length / 1MB
        Write-Host "----------------------------------------------------------" -ForegroundColor Green
        Write-Host ("Successfully generated AppCDS archive: {0:N2} MB" -f $fileSize) -ForegroundColor Green
        Write-Host "Compose Desktop cold-start optimization ready!" -ForegroundColor Green
    } else {
        Write-Warning "AppCDS archive not created. Ensure JVM has JBR/HotSpot CDS features enabled."
    }
} finally {
    Pop-Location
}
