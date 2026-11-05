<#
.SYNOPSIS
    Compiles a custom, ultra-minimal JBR (JetBrains Runtime) for Windows x64.

.DESCRIPTION
    Builds JetBrainsRuntime from source with tailored feature flags:
      - JVM features: compiler1, compiler2, g1gc, zgc, shenandoahgc, cds, link-time-opt;
      - Strips: jvmci, graal, dtrace, jfr, serialgc, parallelgc, epsilongc;
      - Strips: JCEF (--disable-jcef), Projector (--disable-projector), CUPS;
      - Strips debug symbols directly (--with-native-debug-symbols=none);
      - Employs MSVC /O2 /Oi /Ot /GF /Gy /GL LTCG optimization.

.PARAMETER Branch
    Target JBR branch (e.g., 'jbr21' or 'jbr25'). Default: 'jbr21'.

.PARAMETER OutputDir
    Destination folder for the built JDK image.
#>

[CmdletBinding()]
param(
    [string]$Branch = "jbr21",
    [string]$OutputDir = "$PSScriptRoot/../../dist-jbr/windows-x64"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  CPPlayer Custom JBR Builder (Windows MSVC)" -ForegroundColor Cyan
Write-Host "  Target Branch: $Branch" -ForegroundColor Cyan
Write-Host "  Output Dir   : $OutputDir" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$buildDir = Join-Path (Get-Location) "jbr-src"

if (-not (Test-Path "$buildDir/.git")) {
    Write-Host "[1/4] Cloning JetBrainsRuntime ($Branch)..." -ForegroundColor Yellow
    git clone --depth 1 --branch $Branch https://github.com/JetBrains/JetBrainsRuntime.git $buildDir
} else {
    Write-Host "[1/4] Reusing existing checkout in $buildDir" -ForegroundColor Yellow
}

Push-Location $buildDir

try {
    Write-Host "[2/4] Configuring feature flags via bash (MSYS2/Cygwin/WSL environment)..." -ForegroundColor Yellow

    $jvmFeatures = "compiler1,compiler2,g1gc,zgc,shenandoahgc,link-time-opt,cds"
    $configureArgs = @(
        "configure",
        "--with-jvm-variants=server",
        "--with-jvm-features=$jvmFeatures",
        "--with-debug-level=release",
        "--with-native-debug-symbols=none",
        "--disable-warnings-as-errors",
        "--disable-manpages",
        "--disable-docs",
        "--enable-headless-only=no",
        "--disable-jcef",
        "--disable-projector",
        "--with-vendor-name='CPPlayer Custom JBR'",
        "--with-extra-cflags='/O2 /Oi /Ot /GF /Gy /GL'",
        "--with-extra-cxxflags='/O2 /Oi /Ot /GF /Gy /GL'",
        "--with-extra-ldflags='/LTCG /OPT:REF /OPT:ICF'"
    ) -join " "

    & bash -c "$configureArgs"
    if ($LASTEXITCODE -ne 0) {
        throw "Configuration step failed with exit code $LASTEXITCODE"
    }

    Write-Host "[3/4] Building image (make images)..." -ForegroundColor Yellow
    & bash -c "make images LOG=info"
    if ($LASTEXITCODE -ne 0) {
        throw "Compilation step failed with exit code $LASTEXITCODE"
    }

    Write-Host "[4/4] Extracting and pruning output image..." -ForegroundColor Yellow
    $builtImage = Get-ChildItem -Path "$buildDir/build" -Recurse -Directory -Filter "jdk" | Select-Object -First 1 -ExpandProperty FullName
    if (-not $builtImage -or -not (Test-Path "$builtImage/release")) {
        throw "Could not locate built JDK image under $buildDir/build"
    }

    $destPath = [System.IO.Path]::GetFullPath($OutputDir)
    if (Test-Path $destPath) {
        Remove-Item $destPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destPath -Force | Out-Null
    Copy-Item -Path "$builtImage/*" -Destination $destPath -Recurse -Force

    # Run pruning to ensure no dangling diagnostics remain
    $pruneScript = Join-Path $PSScriptRoot "prune-jbr-sdk.ps1"
    if (Test-Path $pruneScript) {
        & $pruneScript -JbrHome $destPath
    }

    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "Custom JBR successfully compiled and packaged into: $destPath" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
} finally {
    Pop-Location
}
