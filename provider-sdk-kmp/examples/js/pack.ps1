$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output = Join-Path $root 'artifacts/provider-sdk'
[IO.Directory]::CreateDirectory($output) | Out-Null
$temporary = Join-Path $output ("pack-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporary) | Out-Null
try {
    $manifest = @{
        id = 'kmp-catalog'; name = 'KMP SDK local catalog'; version = '0.1.0'
        type = 'js'; contract = 'kmp-sdk-v1'; entryPoint = 'index.js'
        capabilities = @('search', 'playback')
        sdkOrigins = @('http://127.0.0.1:48482')
        settingsSchema = @(@{ key = 'origin'; label = 'Catalog origin'; type = 'string'; default = 'http://127.0.0.1:48482' })
    }
    [IO.File]::WriteAllText((Join-Path $temporary 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'index.js') -Destination (Join-Path $temporary 'index.js')
    $zip = Join-Path $output 'kmp-catalog-0.1.0.zip'
    Compress-Archive -Path (Join-Path $temporary '*') -DestinationPath $zip -Force
    Write-Output $zip
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath($output) + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe temporary path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
