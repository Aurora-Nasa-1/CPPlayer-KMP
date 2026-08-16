# Version release flow

## One command

From a clean branch with `origin` configured:

```powershell
# Debug prerelease: debug-v1.2.3 -> GitHub prerelease + debug APK
.\scripts\release.ps1 -Version 1.2.3 -Debug

# Stable release: v1.2.3 -> GitHub release + release APK
.\scripts\release.ps1 -Version 1.2.3
```

The script builds first, creates an annotated Git tag, pushes the commit and tag, and lets GitHub Actions publish the release. Do not manually upload APKs.

## GitHub Actions

- `.github/workflows/debug-release.yml` accepts `debug-v*` tags or manual dispatch. It publishes a prerelease APK named `CPPlayer-<version>-debug.apk`.
- `.github/workflows/release.yml` accepts `v*` tags and publishes the stable artifact.
- Repository Actions must have `Settings -> Actions -> General -> Workflow permissions -> Read and write permissions` enabled.

## In-app update chain

1. The About page calls the GitHub Releases API.
2. It compares SemVer, including prerelease suffixes.
3. Android selects the APK asset and queues it through `DownloadManager` into `Downloads`, with a completion notification.
4. Desktop selects the MSI/DMG/DEB asset and opens the browser; installation remains user-controlled.
5. If no matching asset exists, the release page is opened as a safe fallback.

The version source is `gradle.properties` (`app.versionName`, `app.versionCode`, `app.releaseChannel`). CI overrides these values from the Git tag, so the debug build is visibly marked as a prerelease and never replaces stable metadata.

## Manual smoke test

1. Push a `debug-v1.2.3` tag.
2. Wait for the Debug Release workflow and verify the prerelease asset.
3. Open About -> Check for updates on an older build.
4. Confirm the dialog shows the changelog and download action.
5. On Android, confirm the system download notification and APK in Downloads.
