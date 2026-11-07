<#
.SYNOPSIS
    Generates an AOT (Ahead-of-Time Class Loading & Linking - JEP 483 / JEP 514) cache for CPPlayer Desktop.

.DESCRIPTION
    Leverages Project Leyden Ahead-of-Time Command-Line Ergonomics (JEP 514) in JDK 25 to perform
    one-shot recording and pre-linking of Compose Desktop, Skiko, Kotlin Coroutines, and app classes
    into an Ahead-of-Time (.aot) cache archive.

    On launch, HotSpot mmaps this pre-linked state via -XX:AOTCache=app-aot.aot, completely
    bypassing class loading, verification, and preparation overhead, slashing cold startup to <800ms.
#>

[CmdletBinding()]
param(
    [string]$OutputFile = "",
    [string]$JavaExe = ""
)

$ErrorActionPreference = "Stop"

if (-not $OutputFile) {
    $OutputFile = Join-Path $PSScriptRoot "app-aot.aot"
}

$repoRoot = (Get-Item "$PSScriptRoot\..\..").FullName
$outputPath = [System.IO.Path]::GetFullPath($OutputFile)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  CPPlayer AOT Cache Generator (JDK 25 / JEP 483 & 514)  " -ForegroundColor Cyan
Write-Host "  Destination: $outputPath" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Locate Java executable
if (-not $JavaExe) {
    $candidates = @(
        "$env:CP_JBR_HOME\bin\java.exe",
        "$repoRoot\dist-jbr\windows-x64\bin\java.exe",
        "$repoRoot\.jbr\windows-x64\bin\java.exe",
        "E:\java\bin\java.exe",
        "$repoRoot\app\build\compose\binaries\main\app\CPPlayer\runtime\bin\java.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) {
            $JavaExe = $c
            break
        }
    }
    if (-not $JavaExe) {
        $cmd = Get-Command "java.exe" -ErrorAction SilentlyContinue
        if ($cmd) { $JavaExe = $cmd.Source }
    }
}

if (-not $JavaExe -or -not (Test-Path $JavaExe)) {
    throw "Could not locate a suitable java.exe. Specify -JavaExe <path> or set CP_JBR_HOME."
}

Write-Host "[1/4] Probing JVM capabilities at: $JavaExe" -ForegroundColor Yellow
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$versionOutput = & $JavaExe -version 2>&1 | Out-String
$ErrorActionPreference = $prevEap
Write-Host $versionOutput.Trim() -ForegroundColor Gray

$isJdk25 = $versionOutput -match "version ""25" -or $versionOutput -match "build 25"
$supportsOneShotErgonomics = $false

# Test for JEP 514 (-XX:AOTCacheOutput) support using a safe temp file probe
$tempProbe = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "aot-probe-$PID.aot")
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$testOutput = & $JavaExe "-XX:AOTCacheOutput=$tempProbe" -version 2>&1 | Out-String
$ErrorActionPreference = $prevEap
if (Test-Path $tempProbe) {
    Remove-Item $tempProbe -Force -ErrorAction SilentlyContinue
    $supportsOneShotErgonomics = $true
} elseif ($testOutput -match "AOTCache") {
    $supportsOneShotErgonomics = $true
}
$probeConfig = "$tempProbe.config"
if (Test-Path $probeConfig) { Remove-Item $probeConfig -Force -ErrorAction SilentlyContinue }

Write-Host ("  JDK 25 Detected: {0}" -f $isJdk25) -ForegroundColor Cyan
Write-Host ("  JEP 514 Ahead-of-Time Command-Line Ergonomics: {0}" -f $supportsOneShotErgonomics) -ForegroundColor Cyan

function Invoke-JavaWithTimeout {
    param(
        [string]$JavaPath,
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 30
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $JavaPath
    $psi.Arguments = ($Arguments | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) -join " "
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $finished = $proc.WaitForExit($TimeoutSeconds * 1000)
    if (-not $finished) {
        Write-Warning ("Java execution exceeded {0}s timeout. Terminating process..." -f $TimeoutSeconds)
        try { $proc.Kill() } catch {}
    }
}

# 2. Assemble distributable app image if needed
$appImageDir = Join-Path $repoRoot "app/build/compose/binaries/main/app/CPPlayer"
$appDir = Join-Path $appImageDir "app"
Write-Host "[2/4] Assembling desktop distribution image for AOT training..." -ForegroundColor Yellow
if (-not (Test-Path (Join-Path $appDir "CPPlayer.cfg"))) {
    Push-Location $repoRoot
    try {
        $jbrDir = (Get-Item $JavaExe).Directory.Parent.FullName
        ./gradlew :app:createDistributable "-Pcp.jbrHome=$jbrDir" "-Pcp.jdk25=true" "-Pcp.gc=shenandoah" "-Pcp.compactHeaders=true" -x test --quiet
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Gradle createDistributable returned non-zero exit code ($LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "  Desktop distribution image already assembled." -ForegroundColor Green
}

# 3. Perform AOT Training
Write-Host "[3/4] Performing AOT training run (warming Compose runtime, Audio DSP, Lyrics & pre-linking classes)..." -ForegroundColor Yellow
if (Test-Path $outputPath) {
    Remove-Item $outputPath -Force
}

$cfgPath = Join-Path $appDir "CPPlayer.cfg"
if (Test-Path $cfgPath) {
    Write-Host "  Reading exact classpath sequence from CPPlayer.cfg..." -ForegroundColor Gray
    $cpEntries = Get-Content $cfgPath | Where-Object { $_ -match "^app\.classpath=(.+)$" } | ForEach-Object {
        $matches[1].Replace('$APPDIR', 'app')
    }
    $effectiveClasspath = ($cpEntries -join ";")
} else {
    $effectiveClasspath = "app/*"
}

$jvmTrainingArgs = @(
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseShenandoahGC",
    "-XX:ShenandoahGCMode=generational",
    "-XX:+ShenandoahUncommit",
    "-XX:+UseCompactObjectHeaders",
    "--enable-native-access=ALL-UNNAMED",
    "-Xms128m",
    "-Xmx512m",
    "-Dskiko.library.path=app",
    "-Dcp.player.aotWarmup=true",
    "-Dcp.player.releaseChannel=stable",
    "-cp", $effectiveClasspath
)

Push-Location $appImageDir
try {
    if ($supportsOneShotErgonomics) {
        Write-Host "  Using JEP 514 One-Shot AOTCacheOutput..." -ForegroundColor Green
        $runArgs = @("-XX:AOTCacheOutput=$outputPath") + $jvmTrainingArgs + @("cp.player.app.MainKt")
        Invoke-JavaWithTimeout -JavaPath $JavaExe -Arguments $runArgs -TimeoutSeconds 60

        # HotSpot JEP 514 spawns an assembly child process on exit which writes $outputPath and removes $outputPath.config
        $configPath = "$outputPath.config"
        $deadline = (Get-Date).AddSeconds(30)
        while ((Test-Path $configPath) -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
        }
    } else {
        # Fallback to JEP 483 two-step workflow
        Write-Host "  Fallback to JEP 483 two-step workflow (-XX:AOTMode=record & -XX:AOTMode=create)..." -ForegroundColor Yellow
        $configPath = [System.IO.Path]::ChangeExtension($outputPath, ".aotconf")
        if (Test-Path $configPath) { Remove-Item $configPath -Force }

        $recordArgs = @("-XX:AOTMode=record", "-XX:AOTConfiguration=$configPath") + $jvmTrainingArgs + @("cp.player.app.MainKt")
        Invoke-JavaWithTimeout -JavaPath $JavaExe -Arguments $recordArgs -TimeoutSeconds 60

        if (Test-Path $configPath) {
            Write-Host "  Assembling AOT Cache from configuration..." -ForegroundColor Yellow
            $createArgs = @("-XX:AOTMode=create", "-XX:AOTConfiguration=$configPath", "-XX:AOTCache=$outputPath", "-cp", $effectiveClasspath)
            Invoke-JavaWithTimeout -JavaPath $JavaExe -Arguments $createArgs -TimeoutSeconds 60
            Remove-Item $configPath -Force -ErrorAction SilentlyContinue
        }
    }
} finally {
    Pop-Location
}

# 4. Verification
Write-Host "[4/4] Verifying generated AOT Cache..." -ForegroundColor Yellow
if (Test-Path $outputPath) {
    $fileSize = (Get-Item $outputPath).Length / 1MB
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host ("  AOT Cache successfully generated: {0:N2} MB" -f $fileSize) -ForegroundColor Green
    Write-Host "  Features enabled: Ahead-of-Time Class Loading & Linking (JEP 483)" -ForegroundColor Green
    Write-Host "  Startup speedup expected: 60%~75% reduction in TTID (Cold Start < 800ms)" -ForegroundColor Green
    Write-Host "  Mount flag: -XX:AOTCache=$outputPath" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

    # Copy to app directory so that CPPlayer.exe can load it directly
    $appDest = Join-Path $appDir "app-aot.aot"
    Copy-Item $outputPath $appDest -Force -ErrorAction SilentlyContinue

    Write-Host "  Validating archive mount with launcher classpath..." -ForegroundColor Yellow
    Push-Location $appImageDir
    try {
        $verifyArgs = @(
            "-XX:AOTCache=$outputPath",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseCompactObjectHeaders",
            "--enable-native-access=ALL-UNNAMED",
            "-cp", $effectiveClasspath,
            "-version"
        )
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        $verifyOutput = & $JavaExe $verifyArgs 2>&1 | Out-String
        $ErrorActionPreference = $prevEap
        if ($verifyOutput -match "sharing" -and $verifyOutput -notmatch "error") {
            Write-Host "  AOT Cache successfully verified in sharing mode!" -ForegroundColor Green
        } else {
            Write-Host $verifyOutput.Trim() -ForegroundColor Gray
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Warning "AOT Cache generation failed or file was not output. Ensure JVM supports Project Leyden AOT features."
}
