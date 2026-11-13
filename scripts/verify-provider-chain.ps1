param([switch]$Android)
$ErrorActionPreference = 'Stop'
# Windows only: this verifier invokes gradlew.bat and cannot run on macOS/Linux.
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tag = 'provider-chain-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$build = Join-Path $root "build-verify-$tag"
$cache = Join-Path $root ".gradle-verify-$tag"
$init = Join-Path $root "$tag.init.gradle"
$reports = Join-Path $root "artifacts/provider-chain/$tag"
[IO.Directory]::CreateDirectory($reports) | Out-Null
$xmlRows = [Collections.Generic.List[object]]::new()
Push-Location $root
try {
    node provider-contract/generate.mjs --check
    if ($LASTEXITCODE -ne 0) { throw 'Generated SDK contract is out of date' }
    npm ci --prefix provider-sdk-kmp/js --ignore-scripts --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'SDK TypeScript dependencies could not be installed' }
    npm run --prefix provider-sdk-kmp/js check:types
    if ($LASTEXITCODE -ne 0) { throw 'SDK TypeScript declarations failed validation' }
    $jsReports = Join-Path $reports 'js/test-results'
    [IO.Directory]::CreateDirectory($jsReports) | Out-Null
    node --test --test-reporter=junit provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs > (Join-Path $jsReports 'TEST-sdk.xml')
    if ($LASTEXITCODE -ne 0) { throw 'JavaScript SDK tests failed' }
    [xml]$jsXml = Get-Content -Raw -LiteralPath (Join-Path $jsReports 'TEST-sdk.xml')
    foreach ($suite in $jsXml.SelectNodes('//testsuite')) {
        if ([int]$suite.failures -gt 0 -or [int]$suite.errors -gt 0 -or [int]$suite.skipped -gt 0) {
            throw 'Non-green JavaScript SDK XML'
        }
    }
    $template = @'
allprojects { p ->
    p.layout.buildDirectory.set(new File(p.rootDir, "BUILD_DIRECTORY/${p.name}"))
}
gradle.projectsEvaluated {
    [rootProject.project(':core'), rootProject.project(':app')].each { p ->
        p.tasks.withType(Test).configureEach {
            environment 'no_proxy', '127.0.0.1,localhost'
            jvmArgs '--enable-native-access=ALL-UNNAMED'
            systemProperty 'cp.typed.repo', rootProject.projectDir.absolutePath
        }
    }
}
'@
    [IO.File]::WriteAllText($init, $template.Replace('BUILD_DIRECTORY', "build-verify-$tag"), [Text.UTF8Encoding]::new($false))
    $tasks = @(':app:compileKotlinDesktop', ':core:compileKotlinDesktop', ':provider-sdk-kmp:desktopTest', ':core:desktopTest')
    $coreTests = @(
        'cp.player.core.provider.KmpSdk*',
        'cp.player.core.provider.TypedQuickJsProbeTest',
        'cp.player.core.provider.ProviderConvergenceTest',
        'cp.player.core.provider.ModuleImportAndAutoUpdateTest',
        'cp.player.core.music.*',
        'cp.player.core.media.UnifiedMediaResolverTest',
        'cp.player.core.pluginrepo.*',
        'cp.player.core.cache.*',
        'cp.player.core.control.LocalServerSmokeTest',
        'cp.player.core.playback.PlaybackControllerPreloadTest',
        'cp.player.core.playback.PlaybackCacheContextTest',
        'cp.player.core.playback.LosslessLocalizationWiringTest'
    )
    foreach ($test in $coreTests) { $tasks += @('--tests', $test) }
    $tasks += @(':app:desktopTest', '--tests', 'cp.player.app.repository.MusicRepositoryResourceIdTest')
    if ($Android) { $tasks += @(':core:compileAndroidMain', ':app:compileAndroidMain') }
    & ./gradlew.bat "--project-cache-dir=$cache" -I $init @tasks --continue --console=plain *> (Join-Path $reports 'gradle.log')
    $gradleExit = $LASTEXITCODE
    foreach ($module in @('provider-sdk-kmp', 'core', 'app')) {
        $source = Join-Path $build "$module/test-results/desktopTest"
        if (!(Test-Path -LiteralPath $source)) { throw "Missing $module test-results" }
        $destination = Join-Path $reports "$module/test-results/desktopTest"
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        foreach ($file in Get-ChildItem -LiteralPath $source -Filter 'TEST-*.xml') {
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
            $suite = $xml.testsuite
            $xmlRows.Add([pscustomobject]@{
                module = $module; name = $suite.name
                tests = [int]$suite.tests; failures = [int]$suite.failures
                errors = [int]$suite.errors; skipped = [int]$suite.skipped
            })
        }
    }
    [IO.File]::WriteAllText((Join-Path $reports 'summary.json'), ($xmlRows | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    if ($gradleExit -ne 0) { throw "Gradle failed ($gradleExit): $reports/gradle.log" }
    if ($xmlRows.Count -lt 15 -or ($xmlRows | Where-Object { $_.failures -gt 0 -or $_.errors -gt 0 -or $_.skipped -gt 0 })) {
        throw "Missing or non-green regression XML: $reports"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar = Get-ChildItem -LiteralPath (Join-Path $build 'core/libs') -Filter '*desktop*.jar' | Select-Object -First 1
    if (!$jar) { throw 'Missing desktop core JAR' }
    $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $retired = @($archive.Entries.FullName | Where-Object {
            $_ -match '^cp/player/core/provider/(compat/|HttpProvider|BinaryProvider|JniProvider|DesktopJniProvider|JniWorker|FfmProvider|JsBackendProvider)'
        })
        if ($retired.Count -gt 0) { throw "Retired provider classes remain in production: $retired" }
    } finally { $archive.Dispose() }
    $count = ($xmlRows | Measure-Object -Property tests -Sum).Sum
    Write-Output "Verified $count JVM tests with zero failures/errors/skips; reports: $reports"
} finally {
    Pop-Location
    foreach ($path in @($build, $cache, $init)) {
        $resolved = [IO.Path]::GetFullPath($path)
        if (!$resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar) -or !$resolved.Contains($tag)) {
            throw 'Unsafe verification cleanup target'
        }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
