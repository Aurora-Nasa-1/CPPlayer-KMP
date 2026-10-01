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
