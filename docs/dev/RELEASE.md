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

Desktop assets from both channels: `CPPlayer-<version>-win-Setup.exe` (Windows, Velopack) and `CPPlayer-<version>.deb` (Linux).

The app's update checker only considers tags matching `vX.Y.Z` / `debug-vX.Y.Z`, ignores drafts, and hides prereleases from the `stable` channel. Switching between a debug-signed and a release-signed APK still requires an uninstall — that is a signing-key difference, not a versionCode one.

## GitHub Actions

- `.github/workflows/release.yml` — `v*` tags. Builds and publishes the stable Android release APK.
- `.github/workflows/debug-release.yml` — `debug-v*` tags or manual dispatch. Builds and publishes the debug Android APK.
- `.github/workflows/desktop-release.yml` — `v*` / `debug-v*` tags or manual dispatch. Builds the Windows Velopack installer (`.exe`) and Linux `.deb` on native runners and attaches them to the same release.

All three share the concurrency group `release-<ref>` so they do not race on the same GitHub Release; `softprops/action-gh-release` is idempotent on an existing release, which is the real safety net.

Manual dispatch (`workflow_dispatch`) takes a `version` input; leave it blank to fall back to `app.versionName` in `gradle.properties`. The version is validated as semver and **never** falls back to the branch name.

Repository Actions must have `Settings -> Actions -> General -> Workflow permissions -> Read and write permissions` enabled.

### Signing secrets (optional)

`release.yml` signs the APK only when all four secrets are present:

`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`

With none configured, the build still succeeds and produces an **unsigned** release APK.

### Android R8 (release only)

`app-android` runs R8 with resource shrinking on `release`. Debug builds do not, so **a red stable release is the only place R8 problems surface** — always build `:app-android:assembleRelease` locally before tagging.

Missing classes are a **hard error** for R8, not a warning: `ERROR: Missing classes detected ... mapping/release/missing_rules.txt` fails `minifyReleaseWithR8`. When a dependency drags in JDK-only classes, add `-dontwarn` for them in `app-android/proguard-rules.pro` (current entries: `java.lang.management.*` from Ktor; historical `java.beans.*` from Rhino has been eliminated after migrating to QuickJS). Only do this when the referencing code path is genuinely unreachable on Android — `-dontwarn` does not make the code work, it only stops R8 from refusing to shrink.

## Desktop packaging (icons + installers)

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

### Windows installer — Velopack (no more MSI)

Windows **no longer ships an MSI**. jpackage only produces the app image (`:app:createDistributable`); the `packageWindowsVelopack` Gradle task hands that directory to [Velopack](https://velopack.io) and emits:

| Asset | Role |
|-------|------|
| `CPPlayer-<version>-win-Setup.exe` | what users download — double-click, installed, app launches |
| `CPPlayer-<version>-win-full.nupkg` + `RELEASES` | update feed consumed by Velopack |
| `CPPlayer-<version>-win-portable.zip` | unzip-and-run, mirrors the Linux tar.gz |

Consequently `nativeDistributions.windows { ... }` now carries only `iconFile` — `shortcut` / `menu` / `perUserInstall` / `dirChooser` / `upgradeUuid` were MSI-only and have been removed.

#### Runtime hooks — the app must exit on `--veloapp-*`

`vpk pack --skipVeloAppCheck` only silences vpk's **build-time** check that the app integrates the Velopack SDK. The **runtime** contract still applies: during install / update / uninstall the installer launches the app image with one of

```
--veloapp-install <ver>    --veloapp-updated <ver>
--veloapp-obsolete <ver>   --veloapp-uninstall <ver>
```

and expects the process to exit quickly (30s for install/uninstall, 15s for the update pair). **A process that overruns is killed**, the kill is a non-zero exit code, and Setup then shows its "Install Partially Succeeded" box — 「安装未完成」 — even though the app was installed correctly (shortcuts and the uninstall entry are written regardless). The user sees the app start, vanish ~30s later, get an error dialog, and then the app start again with `VELOPACK_FIRSTRUN=true`.

`exitIfVelopackHook(args)` in `app/src/desktopMain/kotlin/cp/player/app/VelopackHooks.kt` handles all four by exiting with code 0 before the splash window is created. It is called from the **first line** of `main()` — moving it into `runDesktopApp` would let the splash come up first, and `desktopRun` can never reproduce the bug because there is no installer in the picture. Only a real `Setup.exe` run exercises this path.

### Bundled runtime: JetBrains Runtime (JBR)

The desktop packages ship **JBR** instead of temurin, so `WindowDraggableArea` uses the native
`WindowMove` path (edge snapping, drag-out-to-restore, 1:1 cursor tracking) rather than AWT
software movement. `compose.desktop.application.javaHome` points at a pinned JBR that CI downloads
and sha256-checks before `gradlew` runs; `actions/setup-java` (temurin) is **still required** — it
drives the Gradle daemon, compilation and tests, while JBR is only the runtime baked into the
artifact. Version, source channel, and checksums are configured in `app/build.gradle.kts`.

`-Pcp.jbrHome=<dir>` selects an existing runtime; `-Pcp.jbrDownload=true` downloads it on demand
(off by default, so a plain local build is unaffected). When neither resolves, the build proceeds
on the default JDK and says so on stdout — `javaHome` is never set to a bogus path.

> ⚠️ JBR's `.sha256` files are **not published** (cache-redirector returns 403), so the hashes in
> `app/build.gradle.kts` and the workflow are computed from an actual download and must be
> recomputed on every version bump.

> `description` **used to be** a hard ASCII requirement: jpackage wrote it verbatim into the MSI `Package/@Description`, and the MSI database codepage is pinned to **1252** by jpackage's bundled `MsiInstallerStrings_en.wxl`, so any CJK character made `light.exe` fail with **LGHT0311** — reported by jpackage only as `exited with 311 code`, with the real message visible solely under `--verbose`. With MSI gone (2026-10-05) that failure mode no longer exists; `app/build.gradle.kts` now only emits a warning. Keeping it ASCII is still recommended for deb/dmg and older tooling. (`--win-codepage` never landed: JDK-8290471.)

> The old MSI `upgradeUuid` (`4A6A38AA-6BC8-4526-9EEF-92A5499DEB9C`) is **no longer consumed**. Do not lose the value: should Windows packaging ever revert to MSI, it must come back **verbatim** — Windows Installer uses it to recognise a new package as an upgrade of the same product, and a changed UUID makes installs fail with "another version of this product is already installed" while the old version can no longer be removed cleanly.

### Windows Native Image — Nucleus 3.0 and reachability metadata

Nucleus is pinned to `3.0.0-dev-202611061347` in `gradle/libs.versions.toml`. 3.0 removed
`NucleusBackend` / `LocalNucleusBackend`: `nucleusApplication(args) { ... }` is the only entry
point, and `args` must be forwarded (single-instance hand-off, deep links, and
`--veloapp-*` hooks all arrive there).

The GraalVM toolchain requests `version.set("25i3")` instead of a dotted `25.3.4.1`. Nucleus 3.0's
Oracle downloader treats a dotted version as an archive patch and builds
`.../archive/graalvm-jdk-25.3.4.1_...zip`, which is a 404; `25i3` uses the GDS innovation channel
and currently resolves to the same GraalVM 25.3.4.1. CI keeps its own
`GRAALVM_HOME` download, and Nucleus prefers that over provisioning, so release builds stay
deterministic.

Native Image metadata is managed in three layers by `app/build.gradle.kts`:

| Layer | Source | Scope |
|-------|--------|-------|
| L1 | `nucleus.graalvm-runtime` JAR | common reflection / JNI / resource globs |
| L2 | Oracle Reachability Metadata Repository, pinned `1.1.4` | third-party libraries on `runtimeClasspath` |
| L3 | `nucleus/graalvm/platform-metadata/windows-*.json` | AWT / Java2D / fonts on Windows |

`headless = false` keeps the L3 GUI metadata (required for Compose/Skiko),
`autoIncludeResources = true` registers project resource roots, and
`detectOrphanProjectClasses = true` adds no-arg constructors for annotation-processor output.
Do not add hand-written reflection entries first: run
`:app:cleanupGraalvmMetadata` (it reports entries already covered by L1–L3 or static analysis).

#### Profile-Guided Optimization (PGO)

To train and collect a PGO profile on Windows:
1. **GitHub Actions 云端编译（推荐）**：触发 `.github/workflows/desktop-pgo-instrument.yml` 工作流，由 CI 自动使用 Oracle GraalVM 构建插桩版绿色压缩包（`CPPlayer-win-x64-pgo-instrument.zip`）。下载后解压并在本机运行 `CPPlayer.exe`，体验代表性用户场景（播放歌曲、搜索、滚动列表、切歌等），退出后将在同目录下自动落盘 `default.iprof`。
2. **本地编译**：运行 `./gradlew :app:runWithPgoInstrument`。

由于分析期存在 21.8 万个可达方法，GraalVM 在 `--pgo-instrument` 下强制以 level 2 进行插桩编译。配置将并发编译线程限制为 2 (`-H:NumberOfThreads=2`) 并预留 10 GB 堆空间 (`-J-Xmx10g`)，以彻底避免编译器 IR 图膨胀导致的 Java heap space OOM。

采集到的 `default.iprof` 提交到 `app/graalvm/pgo/default.iprof` 后，生产发布构建（CI 与本地）会自动消费该 profile 并启用 `-O3` 终极原生优化。

**当前状态：profile 已入库**（2026-11-14 采集，30,938,646 字节，SHA-256
`6ae1a5ec…e1f2fac`，43,195 types / 324,729 methods；其中 `cp.player` 条目 9,061 条、
`androidx.compose` 6,801 条）。详细的采集场景、重新采集步骤与全部注意事项见
[`app/graalvm/pgo/README.md`](../../app/graalvm/pgo/README.md)。

PGO 是否真的生效，看两处，**不要只看 BUILD SUCCESSFUL**：

1. `app/build/compose/tmp/main/graalvm/nativeCompile/native-image-args.txt` 中出现
   `"--pgo=<绝对路径>"`，且没有 `--pgo-instrument`（插桩版只有 `-Ob`，不能发版）；
2. 构建日志里有 `PGO: applying recorded profile ...`；若出现
   `PGO: recorded profile ... ignored`，说明工具链不是 Oracle GraalVM。

`desktop-release.yml` 的 Windows job 已把这两条做成硬断言 —— profile 被误删、
或构建悄悄退化成「ML 推断版发行包」，发行 job 直接变红。

⚠️ profile 与 GraalVM 版本绑定（Oracle GraalVM 25.3.4.1 / 工具链 `25i3`）；
升级 GraalVM 后必须重新采集，否则 native-image 会报 profile 不兼容。
⚠️ `.gitattributes` 里 `*.iprof binary` 不能删：profile 是单行 JSON，
被 `text=auto eol=lf` 当文本处理后再规范化，`--pgo` 就解析不出来。

### Desktop tray and notifications — no AWT

Nucleus 3.0 has no AWT window backend, so the desktop shell also dropped the AWT tray:

- Tray icon + right-click menu: `dev.nucleusframework:composenativetray` (`2.1.6`).
  Windows uses `Shell_NotifyIcon`, macOS `NSStatusItem`, Linux AppIndicator /
  StatusNotifierItem. The old `SystemTray` / `TrayIcon` / `PopupMenu`, the off-screen
  `TrayMenuScene` image and the `JWindow` menu popup are gone.
- Message notifications: Nucleus `notification-windows` (WinRT Toast) and
  `notification-linux` (freedesktop D-Bus). macOS notification support is
  intentionally not shipped. Clicks carry the `MessageNotification.key`, so each
  conversation can be routed and cancelled precisely; the tray no longer owns
  notification state.
- The old `DesktopWindowPlacement` / `WindowMaximizer` (AWT bounds and work-area
  math) and `JbrWindowChrome` (AWT `WindowDecorations` reflection) are deleted.
  Window placement/maximize now comes from Tao's `WindowState`; `jdk.unsupported`
  remains in the runtime image because `JbrFileDialogBridge` still uses
  `sun.misc.Unsafe` to enable JBR's modern file dialog.

Do **not** re-enable `headless = true` or delete the L3 AWT metadata: Compose/Skiko still
needs AWT/Java2D classes for rendering. The tray and notifications merely stop using AWT
as a window/tray host. Their JNI classes are listed in
`--initialize-at-run-time=...,dev.nucleusframework.composenativetray,dev.nucleusframework.notification,...`
so Native Image does not try to load platform libraries at build time.

## In-app update chain

1. The About page calls the GitHub Releases API.
2. It compares SemVer, including prerelease suffixes.
3. Android selects the APK asset and downloads it through `DownloadManager` into `Downloads`, with a completion notification.
4. Windows selects the `-Setup.exe` asset; Linux prefers the DEB and falls back to TAR.GZ; Android selects the APK. The selected asset's GitHub `browser_download_url` is passed directly to the platform downloader.
5. Android waits for `DownloadManager` completion; desktop streams the installer/archive into CPPlayer's managed download directory without opening a browser. The app verifies non-empty output and the published size, and verifies GitHub's SHA-256 digest when present. After a confirmed destructive update, it records a deferred cleanup marker; it then opens the Android system APK installer, starts the Windows Velopack Setup executable, or opens a Linux `.deb` with the system package handler. OS/user confirmation remains required; this is not a silent install. Cleanup occurs only when the target-or-newer version starts. A `.tar.gz` fallback is opened with the system's default handler, since it has no native installer.

### Remote notices and confirmed destructive updates

`app-update.json` is fetched independently from the repository's `main` branch. Use its `announcements` array for version-bounded notices (`id`, `title`, `message`, optional `url`, `fromVersionInclusive`, and `untilVersionExclusive`). The app records the last dismissed announcement ID and shows each ID once.

`destructiveUpdates` entries gate a warning and a second explicit confirmation using SemVer bounds `fromVersionAtMost` and `toVersionAtLeast`. The checked-in policy covers upgrades from **1.8.5 or earlier** to **1.8.8 or later**, including this **1.8.9** release. It scans module directories and offers cleanup only for manifests whose `apiVersion` is 1 or 3, whose `contract` is not `kmp-sdk-v1`, and whose manifest ID matches the directory name. After confirmation and verified package download, the app only writes a pending cleanup marker; it does not delete anything before installation. On startup, the target-or-newer app revalidates the selected IDs and removes only still-matching legacy module folders before loading providers. If installation is cancelled or fails, the older app version does not apply the marker and keeps the folders. SDK packages and account/cookie data are never selected.

No rollback package is configured or offered by this update flow. The existing v1.8.5 APK may have a lower Android `versionCode` than later installs; Android can reject it as a downgrade, and uninstalling first may erase app data. This release does not silently uninstall or claim a no-data-loss in-place rollback.

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
