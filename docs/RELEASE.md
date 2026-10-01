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

- `.github/workflows/debug-release.yml` accepts `debug-v*` tags or manual dispatch. It publishes Android debug APK and Windows debug MSI assets.
- `.github/workflows/release.yml` accepts `v*` tags and publishes the stable Android artifact.
- `.github/workflows/desktop-release.yml` builds Windows `.msi` and Linux `.deb` packages on native GitHub runners, then attaches them to the same release.
- Repository Actions must have `Settings -> Actions -> General -> Workflow permissions -> Read and write permissions` enabled.

## Desktop packaging (icons + MSI options)

Icons live in two places and are **generated**, not hand-drawn:

```powershell
python scripts/gen_app_icon.py                  # 桌面 + 安卓，一次全出
python scripts/gen_app_icon.py --target android # 只出安卓
python scripts/gen_app_icon.py --preview        # 只出预览图（含安卓圆形遮罩效果）
```

- Desktop: `app/desktop-icons/{icon.ico,icon.icns,icon.png}`
- Android: `app-android/src/main/res/mipmap-*/{ic_launcher_background,ic_launcher_foreground,ic_launcher}.png` plus the handwritten `mipmap-anydpi-v26/ic_launcher.xml`

The generator draws with signed distance fields, so one design stays sharp from 16px to 1024px; the palette follows the app theme (primary `#4F55A5`, secondary `#006A68`). Result files are committed, so CI does not need Python.

Android uses an **adaptive icon**: the background is a full-bleed square (the launcher masks it, so it must not carry its own rounded corners) and the foreground glyph is scaled into the central 66dp safe circle. All three layers come from the same script — never replace just one layer's PNG, or you get a new background under an old glyph.

MSI options are declared in `app/build.gradle.kts` under `nativeDistributions.windows { ... }`: desktop shortcut, Start menu entry, per-user install (no UAC prompt), install-directory chooser, and vendor/description/copyright metadata.

⚠️ **`upgradeUuid` must never change after the first public release.** Windows Installer uses it to recognise a new package as an upgrade of the same product. Change it and installs fail with "another version of this product is already installed", and the old version can no longer be removed cleanly — which also breaks the in-app update chain below (step 4 installs an MSI).

## In-app update chain

1. The About page calls the GitHub Releases API.
2. It compares SemVer, including prerelease suffixes.
3. Android selects the APK asset and queues it through `DownloadManager` into `Downloads`, with a completion notification.
4. Windows selects the MSI/ZIP asset; Linux selects the DEB/TAR.GZ asset. Desktop opens the browser because package installation remains user-controlled.
5. If no matching asset exists, the release page is opened as a safe fallback.

The version source is `gradle.properties` (`app.versionName`, `app.versionCode`, `app.releaseChannel`). CI overrides these values from the Git tag, so the debug build is visibly marked as a prerelease and never replaces stable metadata.

## Local fastrelease phone testing

`fastrelease` is an optimized, locally signed Android build. It is not a store release and can be installed without a release keystore:

```powershell
# Build, install on the first authorized adb device
.\scripts\fastrelease-install.ps1 -Version 1.2.3-local -Launch

# Force a clean build
.\scripts\fastrelease-install.ps1 -Clean -Launch
```

The script runs `:app-android:assembleFastrelease`, checks for `adb`, verifies an authorized device, and installs with `adb install -r -d`. Enable USB debugging and accept the device authorization prompt first. To install manually, use `app-android/build/outputs/apk/fastrelease/app-android-fastrelease.apk`.

## Manual smoke test

1. Push a `debug-v1.2.3` tag.
2. Wait for the Debug Release workflow and verify the prerelease asset.
3. Open About -> Check for updates on an older build.
4. Confirm the dialog shows the changelog and download action.
5. On Android, confirm the system download notification and APK in Downloads.
