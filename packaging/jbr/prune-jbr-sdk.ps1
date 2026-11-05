<#
.SYNOPSIS
    Deep pruning and stripping utility for JBR (JetBrains Runtime) SDK.

.DESCRIPTION
    The official JBR SDK distribution contains extensive debug and diagnostic
    artifacts not needed for production bundling or desktop runtime:
      1. Over 140MB of MSVC debug symbol files (*.pdb);
      2. JDK diagnostics and debugging CLI executables (jdb, jhsdb, jstack, jmap, jconsole, etc.);
      3. Redundant test/legal artifacts.
    This script safely strips those redundant files while preserving jlink, jpackage,
    and JBR native window decorations/move handlers.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$JbrHome = ""
)

if ([string]::IsNullOrWhiteSpace($JbrHome)) {
    $repoRoot = (Get-Item "$PSScriptRoot\..\..").FullName
    $JbrHome = Join-Path $repoRoot ".jbr\windows-x64"
}

$targetPath = [System.IO.Path]::GetFullPath($JbrHome)
if (-not (Test-Path "$targetPath/release")) {
    Write-Error "Invalid JBR directory (missing 'release' file): $targetPath"
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  JBR SDK Deep Prune Utility" -ForegroundColor Cyan
Write-Host "  Target: $targetPath" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$initialSize = (Get-ChildItem -Path $targetPath -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Initial size: {0:N2} MB" -f $initialSize) -ForegroundColor Yellow

# 1. Remove MSVC debug symbols (*.pdb)
$pdbFiles = Get-ChildItem -Path $targetPath -Recurse -Filter "*.pdb" -Force -ErrorAction SilentlyContinue
$pdbCount = $pdbFiles.Count
$pdbSize = ($pdbFiles | Measure-Object -Property Length -Sum).Sum / 1MB
if ($pdbCount -gt 0) {
    Write-Host ("Removing {0} debug symbol files (*.pdb, total {1:N2} MB)..." -f $pdbCount, $pdbSize)
    $pdbFiles | Remove-Item -Force -ErrorAction SilentlyContinue
}

# 2. Remove diagnostic/debug CLI tools
$unneededExes = @(
    "jaccessinspector.exe", "jaccesswalker.exe", "jabswitch.exe",
    "jdb.exe", "jhsdb.exe", "jinfo.exe", "jmap.exe", "jps.exe",
    "jrunscript.exe", "jshell.exe", "jstack.exe", "jstat.exe", "jstatd.exe",
    "jwebserver.exe", "jdeprscan.exe", "jcmd.exe", "jconsole.exe",
    "kinit.exe", "klist.exe", "ktab.exe", "rmiregistry.exe", "serialver.exe"
)

$removedCount = 0
foreach ($exe in $unneededExes) {
    $exePath = Join-Path "$targetPath/bin" $exe
    if (Test-Path $exePath) {
        Remove-Item $exePath -Force -ErrorAction SilentlyContinue
        $removedCount++
    }
}
Write-Host ("Removed {0} unused CLI diagnostic executables." -f $removedCount)

# 3. Strip redundant CDS archives (*nocoops*.jsa)
$redundantJsa = Get-ChildItem -Path "$targetPath/bin/server" -Filter "*nocoops*.jsa" -Force -ErrorAction SilentlyContinue
if ($redundantJsa) {
    $jsaSize = ($redundantJsa | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host ("Removing {0} redundant non-compressed-oops CDS archives (total {1:N2} MB)..." -f $redundantJsa.Count, $jsaSize)
    $redundantJsa | Remove-Item -Force -ErrorAction SilentlyContinue
}

# 4. Verify critical runtime components remain intact
$criticalItems = @("bin/java.exe", "bin/javaw.exe", "bin/jlink.exe", "bin/jpackage.exe", "bin/awt.dll", "lib", "release")
foreach ($item in $criticalItems) {
    $itemPath = Join-Path $targetPath $item
    if (-not (Test-Path $itemPath)) {
        Write-Error "Safety assertion failed: missing critical component: $item"
        exit 1
    }
}

$finalSize = (Get-ChildItem -Path $targetPath -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB
$savedSize = $initialSize - $finalSize
Write-Host "----------------------------------------------------------" -ForegroundColor Green
Write-Host ("Final size: {0:N2} MB" -f $finalSize) -ForegroundColor Green
Write-Host ("Saved size: {0:N2} MB ({1:N1}%)" -f $savedSize, ($savedSize / $initialSize * 100)) -ForegroundColor Green
Write-Host "JBR SDK pruning completed successfully!" -ForegroundColor Green
