# Version release flow

## One command

From a clean branch with `origin` configured:

```powershell
# Debug prerelease: debug-v1.2.3 -> GitHub prerelease + debug APK
.\scripts\release.ps1 -Version 1.2.3 -DebugBuild

# Stable release: v1.2.3 -> GitHub release + release APK
.\scripts\release.ps1 -Version 1.2.3
```

The script requires a clean working tree, builds first, creates an annotated Git tag, pushes the tag, and lets GitHub Actions publish the release. Do not manually upload APKs.

> ⚠️ `release.ps1` 用 `git push` 推 tag，**本机没有 git 凭据**（见 `.workbuddy-ai/memory/`）——
> 推送这一步要么由你在有凭据的终端里执行，要么先配好 token。没有凭据时
> `git push` 会卡在凭据提示上被 SIGTERM，表现为「无输出、无效果」。

## Version numbering — one source of truth

`versionName` / `releaseChannel` come from `gradle.properties`. **`versionCode` is not stored anywhere — it is derived from `versionName`** by the root `build.gradle.kts`:

```
versionCode = major * 1_000_000 + minor * 1_000 + patch
```

Examples: `1.0.0` → `1000000`, `1.2.3` → `1002003`, `2.0.0` → `2000000`.
A prerelease suffix is ignored: `1.2.3-beta.2` and `1.2.3` share a code, so the stable build can replace the prerelease.

`:app` (desktop) and `:app-android` both read these values from `rootProject.extra` — **never re-implement the formula in a module, a workflow, or a script.** CI passes only `-Papp.versionName` and `-Papp.releaseChannel`. Gradle prints the resolved values on every configure, so CI logs show exactly which version was built:

```
CPPlayer build metadata: 1.2.3 (1002003) channel=stable sha=8accc68
```

To override the code explicitly, pass `-Papp.versionCode=` — only `scripts/fastrelease-install.ps1` does this on purpose.

> 历史坑（已修）：versionCode 曾在四条路径上各算各的 —— `gradle.properties` 写死 `1`、
> `release.yml` 用 `GITHUB_RUN_NUMBER`、`debug-release.yml` 用 `100000 + GITHUB_RUN_NUMBER`、
> `release.ps1` 用 `major*10000 + minor*100 + patch`。后果是**版本号涨了、versionCode 反而变小**
> （例如脚本出的 `v1.0.1` = 10001，CI 出的 `v1.0.2` = 运行序号 7），Android 直接判成降级、
> 拒绝安装。顺带一提，`100` 的位宽本身也会撞码：`1.0.100` 和 `1.1.0` 都是 `10100`。

## Tag → channel

| Tag | Channel | Release | Android asset |
|-----|---------|---------|---------------|
| `v1.2.3` | `stable` | normal release | `CPPlayer-1.2.3-android.apk` |
| `debug-v1.2.3` | `debug` | prerelease | `CPPlayer-1.2.3-android-debug.apk` |

Desktop assets from both channels: `CPPlayer-<version>.msi` (Windows) and `CPPlayer-<version>.deb` (Linux).

The app's update checker only considers tags matching `vX.Y.Z` / `debug-vX.Y.Z`, ignores drafts, and hides prereleases from the `stable` channel. Switching between a debug-signed and a release-signed APK still requires an uninstall — that is a signing-key difference, not a versionCode one.

## GitHub Actions

- `.github/workflows/release.yml` — `v*` tags. Builds and publishes the stable Android release APK.
- `.github/workflows/debug-release.yml` — `debug-v*` tags or manual dispatch. Builds and publishes the debug Android APK.
- `.github/workflows/desktop-release.yml` — `v*` / `debug-v*` tags or manual dispatch. Builds Windows `.msi` and Linux `.deb` on native runners and attaches them to the same release.

All three share the concurrency group `release-<ref>` so they do not race on the same GitHub Release; `softprops/action-gh-release` is idempotent on an existing release, which is the real safety net.

Manual dispatch (`workflow_dispatch`) takes a `version` input; leave it blank to fall back to `app.versionName` in `gradle.properties`. The version is validated as semver and **never** falls back to the branch name.

Repository Actions must have `Settings -> Actions -> General -> Workflow permissions -> Read and write permissions` enabled.

### Signing secrets (optional)

`release.yml` signs the APK only when all four secrets are present:

`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`

With none configured, the build still succeeds and produces an **unsigned** release APK.

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

Desktop build metadata reaches the app through `compose.desktop.application.jvmArgs` in `app/build.gradle.kts` (`-Dcp.player.versionName`, `.versionCode`, `.releaseChannel`, `.gitSha`) and is read by `BuildInfo`. **Gradle properties do not become JVM system properties on their own** — dropping those lines silently makes the About page report `v1.0.0 (1)` / `unknown` forever, regardless of the tag.

## Local fastrelease phone testing

`fastrelease` is an optimized, locally signed Android build. It is not a store release and can be installed without a release keystore:

```powershell
# Build, install on the first authorized adb device
.\scripts\fastrelease-install.ps1 -Version 1.2.3-local -Launch

# Force a clean build
.\scripts\fastrelease-install.ps1 -Clean -Launch
```

The script runs `:app-android:assembleFastrelease`, checks for `adb`, verifies an authorized device, and installs with `adb install -r -d`. Enable USB debugging and accept the device authorization prompt first. To install manually, use `app-android/build/outputs/apk/fastrelease/app-android-fastrelease.apk`.

It pins `app.versionCode=900001` on purpose — below the derived `1000000` for `1.0.0`, so a real release can always replace a local build, while `-d` lets the local build go on top of a release.

## Manual smoke test

1. Push a `debug-v1.2.3` tag.
2. Wait for the Debug Release workflow and verify the prerelease asset.
3. Open About -> Check for updates on an older build.
4. Confirm the dialog shows the changelog and download action.
5. On Android, confirm the system download notification and APK in Downloads.
6. Check the workflow log for the `CPPlayer build metadata:` line and confirm the version matches the tag.
