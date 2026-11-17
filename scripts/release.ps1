param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    # Only pass this to override the derived rule (leave 0 = let Gradle derive it
    # from $Version). The single implementation of "versionName -> versionCode"
    # is cpVersionCodeOf() in the root build.gradle.kts.
    [int]$VersionCode = 0,

    [switch]$DebugBuild
)

$ErrorActionPreference = "Stop"

# 1. Check version format
if ($Version -notmatch '^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "Version must be semver, for example 1.2.3 or 1.2.3-beta.1"
}

# 2. Build configuration
$channel = if ($DebugBuild) { "debug" } else { "stable" }
$tagPrefix = if ($DebugBuild) { "debug-v" } else { "v" }
$gradleTask = if ($DebugBuild) { ":app-android:assembleDebug" } else { ":app-android:assembleRelease" }
$tagName = "$tagPrefix$Version"

# 3. Working tree must be clean.
# Release from a committed state: with a dirty tree the tagged commit and the
# artifact that was actually built are not the same thing.
git diff --quiet
if ($LASTEXITCODE -ne 0) { throw "Tracked files have unstaged changes. Commit or 'git stash -u' first." }
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { throw "Staged but uncommitted changes. Commit first." }

# 4. Check the tag does not exist locally or on the remote
$existingTag = git tag -l $tagName
if ($existingTag) { throw "Tag '$tagName' already exists locally." }
$remoteTag = git ls-remote --tags origin "refs/tags/$tagName"
if ($LASTEXITCODE -ne 0) { throw "Could not reach 'origin' to check for existing tags." }
if ($remoteTag) { throw "Tag '$tagName' already exists on origin." }

# 4.1 Pre-flight checks: Android Baseline Profile & Desktop Native/JVM reminders
$profPath = Join-Path $PSScriptRoot "..\app-android\src\main\baselineProfiles\baseline-prof.txt"
if (Test-Path $profPath) {
    $profTime = (Get-Item $profPath).LastWriteTime
    Write-Host "Pre-flight: Android Baseline Profile detected (Last modified: $profTime)." -ForegroundColor Gray
} else {
    Write-Warning "Android Baseline Profile not found at $profPath. Consider running scripts/collect-baseline-profile.ps1 before release."
}
Write-Host "Pre-flight: Windows uses Oracle GraalVM Native Image (Serial GC, 128 MiB max Java heap, CompactingOldGen); Linux packages remain JVM. PGO applies when its profile exists." -ForegroundColor Gray
$pgoProfilePath = Join-Path $PSScriptRoot "..\app\graalvm\pgo\default.iprof"
if (Test-Path $pgoProfilePath) {
    # 只判「文件在」不够：--pgo-instrument 的镜像如果没正常退出，profile 根本不会落盘；
    # 而中途被杀后残留的半截文件同样存在。真正的 profile 是几十 MB 的 JSON。
    $pgoLength = (Get-Item $pgoProfilePath).Length
    $pgoSha256 = (Get-FileHash -LiteralPath $pgoProfilePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($pgoLength -lt 1MB) {
        Write-Warning "PGO profile at $pgoProfilePath is only $pgoLength bytes — that is not a trained profile (instrumented run probably exited abnormally). Recollect before shipping a Windows build."
    } else {
        Write-Host ("Pre-flight: Windows PGO profile {0:N0} bytes, SHA-256 {1} — release native-image will run with --pgo (Oracle GraalVM 25.3.4.1 only)." -f $pgoLength, $pgoSha256) -ForegroundColor Gray
    }
} else {
    Write-Warning "Windows PGO profile is missing at $pgoProfilePath. Without it the release falls back to ML Profile Inference; recollect via .\gradlew :app:runWithPgoInstrument, or trigger .github/workflows/desktop-pgo-instrument.yml."
}

# 5. Execute Gradle build
# Do NOT pass app.versionCode by default: Gradle derives it from app.versionName
# (major*1_000_000 + minor*1_000 + patch). This script used to compute
# major*10000 + minor*100 + patch locally while CI used GITHUB_RUN_NUMBER, so one
# version could end up with two different codes; and the 100-wide field collided
# anyway (1.0.100 and 1.1.0 both became 10100).
# Gradle prints "CPPlayer build metadata: <version> (<code>) ..." while
# configuring, which is how you verify what actually got built.
Write-Host "Building $channel version with task $gradleTask (version $Version)..." -ForegroundColor Cyan
$gradleArgs = @($gradleTask, "--no-daemon", "-Papp.versionName=$Version", "-Papp.releaseChannel=$channel")
if ($VersionCode -ne 0) { $gradleArgs += "-Papp.versionCode=$VersionCode" }
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

# 6. Push tag
git tag -a $tagName -m "Release $Version ($channel)"
if ($LASTEXITCODE -ne 0) { throw "Failed to create tag '$tagName'." }
git push origin $tagName
if ($LASTEXITCODE -ne 0) { throw "Failed to push tag '$tagName'." }

Write-Host "Published $tagName. GitHub Actions will upload the artifact." -ForegroundColor Green
