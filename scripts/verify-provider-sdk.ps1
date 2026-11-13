param([switch]$Android)
$ErrorActionPreference = 'Stop'
# Windows only: this verifier invokes gradlew.bat and cannot run on macOS/Linux.
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tag = 'sdk-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$cache = Join-Path $root ".gradle-$tag"
$build = Join-Path $root "build-verify-$tag"
$init = Join-Path $root "$tag.init.gradle"
$artifacts = Join-Path $root "artifacts/provider-sdk/$tag"
[IO.Directory]::CreateDirectory($artifacts) | Out-Null
function Check-Exit($name) { if ($LASTEXITCODE -ne 0) { throw "$name failed ($LASTEXITCODE)" } }
Push-Location $root
try {
    node provider-contract/generate.mjs --check
    Check-Exit 'Generated contract check'
    npm ci --prefix provider-sdk-kmp/js --ignore-scripts --no-audit --no-fund
    Check-Exit 'SDK TypeScript dependency installation'
    npm run --prefix provider-sdk-kmp/js check:types
    Check-Exit 'SDK TypeScript declarations'
    node --test --test-reporter=junit provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs > (Join-Path $artifacts 'TEST-js-sdk.xml')
    Check-Exit 'JS SDK tests'
    $template = @'
allprojects { p ->
    p.layout.buildDirectory.set(new File(p.rootDir, "BUILD_DIRECTORY/${p.name}"))
}
gradle.projectsEvaluated {
    rootProject.project(':core').tasks.withType(Test).configureEach {
        environment 'no_proxy', '127.0.0.1,localhost'
        jvmArgs '--enable-native-access=ALL-UNNAMED'
        systemProperty 'cp.typed.repo', rootProject.projectDir.absolutePath
    }
}
'@
    [IO.File]::WriteAllText($init, $template.Replace('BUILD_DIRECTORY', "build-verify-$tag"), [Text.UTF8Encoding]::new($false))
    $tasks = @(':app:compileKotlinDesktop', ':core:compileKotlinDesktop', ':provider-sdk-kmp:desktopTest', ':core:desktopTest',
        '--tests', 'cp.player.core.provider.KmpSdk*',
        '--tests', 'cp.player.core.provider.TypedQuickJsProbeTest',
        '--tests', 'cp.player.core.provider.ProviderSdkContractTest',
        '--tests', 'cp.player.core.provider.RecommendationsSdkTest',
        '--tests', 'cp.player.core.music.UnifiedMusicSourceContractTest')
    if ($Android) { $tasks += @(':core:compileAndroidMain', ':app:compileAndroidMain', ':app-android:assembleDebug') }
    & ./gradlew.bat "--project-cache-dir=$cache" -I $init @tasks --continue --console=plain *> (Join-Path $artifacts 'gradle.log')
    $gradleExit = $LASTEXITCODE
    $reports = @(Get-ChildItem -LiteralPath $build -Recurse -Filter 'TEST-*.xml' | Where-Object { $_.FullName -match 'test-results' })
    foreach ($report in $reports) {
        $destination = Join-Path $artifacts ($report.Directory.Parent.Name + '-' + $report.Directory.Name)
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        Copy-Item -LiteralPath $report.FullName -Destination $destination
        [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
        if ([int]$xml.testsuite.failures -gt 0 -or [int]$xml.testsuite.errors -gt 0 -or [int]$xml.testsuite.skipped -gt 0) {
            throw "Non-green XML: $($report.Name)"
        }
    }
    if ($gradleExit -ne 0) { throw "Gradle failed ($gradleExit); see $artifacts/gradle.log" }
    if ($reports.Count -lt 6) { throw 'Expected contract/probe/legacy/benchmark XML reports are missing' }
    if ($Android) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $apks = @(Get-ChildItem -LiteralPath (Join-Path $build 'app-android/outputs/apk/debug') -Filter '*.apk')
        if ($apks.Count -eq 0) { throw 'Android APK output is missing' }
        foreach ($apk in $apks) {
            $archive = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
            try {
                if (!($archive.Entries.FullName -match 'composeResources/cp.player.app.resources')) { throw 'APK Compose resources missing' }
                $archive.Entries.FullName | Where-Object { $_ -match '^lib/|composeResources/cp.player.app.resources' } |
                    Out-File -LiteralPath (Join-Path $artifacts 'apk-contents.txt') -Encoding utf8
            } finally { $archive.Dispose() }
            Copy-Item -LiteralPath $apk.FullName -Destination $artifacts
        }
    }
    Get-ChildItem -LiteralPath (Join-Path $root 'artifacts/provider-sdk') -Filter 'benchmark-*.json' |
        Copy-Item -Destination $artifacts
    Write-Output "Verified XML with zero skips: $artifacts"
} finally {
    Pop-Location
    # Only delete this invocation's unique scaffolding, never a shared build tree.
    foreach ($path in @($build, $cache, $init)) {
        $resolved = [IO.Path]::GetFullPath($path)
        if (!$resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar) -or !$resolved.Contains($tag)) { throw 'Unsafe cleanup target' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
