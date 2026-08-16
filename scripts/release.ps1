param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [int]$VersionCode = 0,

    [switch]$DebugBuild
)

$ErrorActionPreference = "Stop"

# 1. Check version format
if ($Version -notmatch '^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:[-.][0-9A-Za-z.-]+)?$') {
    throw "Version must be semver, for example 1.2.3 or 1.2.3-beta.1"
}

# 2. Auto calculate VersionCode
if ($VersionCode -eq 0) {
    $major = [int]$Matches['major']
    $minor = [int]$Matches['minor']
    $patch = [int]$Matches['patch']
    $VersionCode = ($major * 10000) + ($minor * 100) + $patch
}

# 3. Check git status early
git diff --exit-code -- gradle.properties
if ($LASTEXITCODE -ne 0) {
    throw "Commit or stash local gradle.properties changes first"
}

# 4. Switch build configuration
$channel = if ($DebugBuild) { "debug" } else { "stable" }
$tagPrefix = if ($DebugBuild) { "debug-v" } else { "v" }
$gradleTask = if ($DebugBuild) { ":androidApp:assembleDebug" } else { ":androidApp:assembleRelease" }

$tagName = "$tagPrefix$Version"

# 5. Check if tag exists
$existingTag = git tag -l $tagName
if ($existingTag) {
    throw "Tag '$tagName' already exists locally."
}

# 6. Execute Gradle build
Write-Host "Building $channel version with task $gradleTask (VersionCode: $VersionCode)..."
& .\gradlew.bat $gradleTask --no-daemon "-Papp.versionName=$Version" "-Papp.versionCode=$VersionCode" "-Papp.releaseChannel=$channel"
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed"
}

# 7. Push Tag
git tag -a $tagName -m "Release $Version ($channel)"
git push origin $tagName

Write-Host "Published $tagName. GitHub Actions will upload the artifact." -ForegroundColor Green