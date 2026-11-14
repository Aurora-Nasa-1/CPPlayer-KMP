# 发布流程

> 版本号规则、tag 与通道、CI、桌面打包、更新链。适用读者：维护者。

## 1. 一条命令

前提：分支干净，`origin` 已配置。

```powershell
# debug 预发布：debug-v1.2.3 -> GitHub prerelease + debug APK
.\scripts\release.ps1 -Version 1.2.3 -DebugBuild

# 正式发布：v1.2.3 -> GitHub release + release APK
.\scripts\release.ps1 -Version 1.2.3
```

脚本要求工作区干净，先构建，再创建附注 tag、推 tag，由 GitHub Actions 发布。不要手工上传 APK。

| 前提 | 说明 |
|---|---|
| git 凭据 | `release.ps1` 用 `git push` 推 tag。本机无凭据时推送会卡在凭据提示上被 SIGTERM，表现为「无输出、无效果」；需在有凭据的终端执行或先配 token |

## 2. 版本号

`versionName` 与 `releaseChannel` 来自 `gradle.properties`。`versionCode` 不存储，由根 `build.gradle.kts` 从 `versionName` 推导：

```text
versionCode = major * 1_000_000 + minor * 1_000 + patch
```

| `versionName` | `versionCode` |
|---|---|
| `1.0.0` | `1000000` |
| `1.2.3` | `1002003` |
| `2.0.0` | `2000000` |
| `1.2.3-beta.2` | 与 `1.2.3` 同码，正式包可替换预发布 |

| 规则 | 内容 |
|---|---|
| 唯一公式 | `:app` 与 `:app-android` 都从 `rootProject.extra` 读；不在模块、workflow、脚本里重写公式 |
| CI 传参 | 只传 `-Papp.versionName` 与 `-Papp.releaseChannel` |
| 日志 | 每次 configure 打印 `CPPlayer build metadata: 1.2.3 (1002003) channel=stable sha=8accc68` |
| 显式覆盖 | `-Papp.versionCode=`；只有 `scripts/fastrelease-install.ps1` 有意使用 |

历史坑（已修）：`versionCode` 曾在四条路径各算各的 —— `gradle.properties` 写死 `1`、`release.yml` 用 `GITHUB_RUN_NUMBER`、`debug-release.yml` 用 `100000 + GITHUB_RUN_NUMBER`、`release.ps1` 用 `major*10000 + minor*100 + patch`。后果是版本号涨了、`versionCode` 反而变小，Android 判成降级并拒绝安装。另外 `100` 的位宽本身会撞码：`1.0.100` 与 `1.1.0` 都是 `10100`。

## 3. tag 与通道

| tag | 通道 | Release | Android 资产 |
|---|---|---|---|
| `v1.2.3` | `stable` | 正式 | `CPPlayer-1.2.3-android.apk` |
| `debug-v1.2.3` | `debug` | 预发布 | `CPPlayer-1.2.3-android-debug.apk` |

桌面资产两个通道相同：`CPPlayer-<version>-win-Setup.exe`（Windows，Velopack）、`CPPlayer-<version>.deb`（Linux）。

| 规则 | 内容 |
|---|---|
| 更新检查 | 只认 `vX.Y.Z` / `debug-vX.Y.Z`，忽略 draft，`stable` 通道隐藏 prerelease |
| 签名差异 | debug 签名与 release 签名的 APK 互装需先卸载；这是签名差异，不是 versionCode |

## 4. GitHub Actions

| workflow | 触发 | 产物 |
|---|---|---|
| `release.yml` | `v*` tag | 正式 Android APK |
| `debug-release.yml` | `debug-v*` tag 或手动 | debug Android APK |
| `desktop-release.yml` | `v*` / `debug-v*` 或手动 | Windows Velopack `.exe`、Linux `.deb`，附到同一个 release |

| 项 | 内容 |
|---|---|
| 并发 | 三者共用 `release-<ref>` 并发组，避免抢同一个 GitHub Release；`softprops/action-gh-release` 对已存在的 release 幂等，这才是真正的安全网 |
| 手动触发 | `workflow_dispatch` 收 `version` 输入；留空回退 `gradle.properties` 的 `app.versionName`；按 semver 校验，绝不回退成分支名 |
| 仓库权限 | Settings → Actions → General → Workflow permissions → Read and write permissions |

### 4.1 签名密钥（可选）

`release.yml` 只在四个 secret 齐全时签名 APK：

```text
RELEASE_KEYSTORE_BASE64
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

全缺时构建仍成功，产出未签名的 release APK。

### 4.2 Android R8（仅 release）

`app-android` 在 `release` 跑 R8 + 资源压缩。debug 不跑，所以 R8 问题只在正式发布暴露：打 tag 前先本地 `:app-android:assembleRelease`。

| 项 | 内容 |
|---|---|
| 缺类是硬错误 | `ERROR: Missing classes detected ... mapping/release/missing_rules.txt` 会让 `minifyReleaseWithR8` 失败 |
| 处理 | 在 `app-android/proguard-rules.pro` 加 `-dontwarn`；当前条目：Ktor 带来的 `java.lang.management.*`（历史 `java.beans.*` 随 Rhino → QuickJS 迁移已消除） |
| 前提 | 只在该代码路径确实在 Android 不可达时才加；`-dontwarn` 不让代码能跑，只让 R8 不再拒绝压缩 |

## 5. 桌面打包

### 5.1 图标

图标是生成的，不是手绘。

```powershell
python scripts/gen_app_icon.py                  # 桌面 + 安卓，一次全出
python scripts/gen_app_icon.py --target android # 只出安卓
python scripts/gen_app_icon.py --preview        # 只出预览图（含安卓圆形遮罩效果）
```

| 平台 | 产物 |
|---|---|
| 桌面 | `app/desktop-icons/{icon.ico,icon.icns,icon.png}` |
| Android | `app-android/src/main/res/mipmap-*/{ic_launcher_background,ic_launcher_foreground,ic_launcher}.png` + 手写 `mipmap-anydpi-v26/ic_launcher.xml` |

| 约定 | 内容 |
|---|---|
| 绘制方式 | 有符号距离场，一份设计从 16px 到 1024px 都锐利 |
| 配色 | 跟随主题：primary `#4F55A5`，secondary `#006A68` |
| 提交 | 产物入库，CI 不需要 Python |
| 自适应图标 | 背景是全出血方块（启动器负责遮罩，不能自带圆角），前景字形缩进中央 66dp 安全圆 |
| 禁止 | 只替换某一层 PNG：会出现新背景配旧字形 |

### 5.2 Windows 安装包（Velopack）

Windows 不再出 MSI。jpackage 只出 app image（`:app:createDistributable`），`packageWindowsVelopack` 把它交给 [Velopack](https://velopack.io) 产出：

| 资产 | 作用 |
|---|---|
| `CPPlayer-<version>-win-Setup.exe` | 用户下载的安装包 |
| `CPPlayer-<version>-win-full.nupkg` + `RELEASES` | Velopack 更新源 |
| `CPPlayer-<version>-win-portable.zip` | 解压即用，对应 Linux 的 tar.gz |

`nativeDistributions.windows { ... }` 因此只剩 `iconFile`：`shortcut` / `menu` / `perUserInstall` / `dirChooser` / `upgradeUuid` 都是 MSI-only，已移除。

发布资产是白名单，不是「打包目录有什么就发什么」。deb / tar.gz 任务会把中间产物留在真产物旁边（图标集 `16x16.png` … `512x512.png`、`after-install-nucleus.tpl`、`package.json` / `latest-linux.yml`）。`dir/*` glob 会把它们带进 CI artifact，`files: release-assets/**/*` 曾把 v1.9.0 的 10 个中间文件一起发布。现在两道闸：`assetPaths` 收窄到 `*.deb` / `*.tar.gz`，上传前 `Prune non-release assets` 按扩展名过滤。新增资产类型改这两处，不要放松 glob。

#### 运行期钩子必须由应用处理

`vpk pack --skipVeloAppCheck` 只免掉 vpk 的构建期检查。运行期契约仍生效：安装 / 更新 / 卸载时，安装器用下列参数拉起 app image，并要求进程尽快退出（install / uninstall 30 秒，update 那一对 15 秒）：

```text
--veloapp-install <ver>    --veloapp-updated <ver>
--veloapp-obsolete <ver>   --veloapp-uninstall <ver>
```

超时即被杀，杀进程是非 0 退出码，Setup 随后显示「安装未完成」，尽管应用其实装好了（快捷方式与卸载项照写）。用户看到的是：应用启动、约 30 秒后消失、弹错误框、再用 `VELOPACK_FIRSTRUN=true` 启动一次。

`app/src/desktopMain/kotlin/cp/player/app/VelopackHooks.kt` 的 `exitIfVelopackHook(args)` 处理四种情况，在创建启动画面之前以 0 退出，且由 `main()` 第一行调用。放进 `runDesktopApp` 会让启动画面先出现；`desktopRun` 永远复现不了，因为没有安装器，只有真实 `Setup.exe` 才走这条路径。

### 5.3 内置运行时 JBR

桌面包内置 JBR 而不是 temurin，`WindowDraggableArea` 才能走原生 `WindowMove`（边缘吸附、拖出还原、1:1 跟手），而不是 AWT 软件移动。

| 项 | 内容 |
|---|---|
| `javaHome` | 指向 CI 下载并做 sha256 校验的固定 JBR，在 `gradlew` 之前完成 |
| `actions/setup-java` | 仍然必需：它驱动 Gradle daemon、编译与测试；JBR 只是打进产物的运行时 |
| 配置位置 | 版本、来源通道、校验值都在 `app/build.gradle.kts` |
| `-Pcp.jbrHome=<dir>` | 用现成运行时 |
| `-Pcp.jbrDownload=true` | 按需下载（默认关，普通本地构建不受影响） |
| 都无法解析时 | 用默认 JDK 继续，并在 stdout 说明；`javaHome` 绝不设成错路径 |

JBR 的 `.sha256` 文件未发布（cache-redirector 返回 403），`app/build.gradle.kts` 与 workflow 里的哈希是实际下载后算的，每次升版本都要重算。

`description` 曾是硬 ASCII 要求：jpackage 把它原样写进 MSI 的 `Package/@Description`，而 MSI 数据库 codepage 被 `MsiInstallerStrings_en.wxl` 钉死 1252，任何 CJK 字符都让 `light.exe` 报 LGHT0311，jpackage 只报 `exited with 311 code`，真实信息只在 `--verbose` 下可见。MSI 退役后该失败模式不存在，`app/build.gradle.kts` 现在只告警；仍建议保持 ASCII，deb / dmg 与旧工具链会读它。（`--win-codepage` 从未落地：JDK-8290471。）

旧 MSI 的 `upgradeUuid`（`4A6A38AA-6BC8-4526-9EEF-92A5499DEB9C`）不再被消费，但值不能丢：若将来回到 MSI，必须原样恢复。Windows Installer 用它在升级时识别同一产品，UUID 变了会报「另一个版本已安装」且旧版本无法干净卸载。

### 5.4 Native Image（Nucleus 3.0）

Nucleus 固定在 `gradle/libs.versions.toml` 的 `3.0.0-dev-202611061347`。3.0 移除了 `NucleusBackend` / `LocalNucleusBackend`：`nucleusApplication(args) { ... }` 是唯一入口，且必须转发 `args`（单实例移交、深链、`--veloapp-*` 钩子都从那里进来）。

| 项 | 内容 |
|---|---|
| GraalVM 版本写法 | `version.set("25i3")`，不写 `25.3.4.1` |
| 原因 | Nucleus 3.0 的 Oracle 下载器把点分版本当 archive patch，拼出 404 的 URL；`25i3` 走 GDS innovation 通道，目前解析到同一个 GraalVM 25.3.4.1 |
| CI | 保留自己的 `GRAALVM_HOME` 下载，Nucleus 优先用它，发布构建可复现 |

元数据三层，由 `app/build.gradle.kts` 管理：

| 层 | 来源 | 范围 |
|---|---|---|
| L1 | `nucleus.graalvm-runtime` JAR | 通用反射 / JNI / 资源 glob |
| L2 | Oracle Reachability Metadata Repository，固定 `1.1.4` | `runtimeClasspath` 上的第三方库 |
| L3 | `nucleus/graalvm/platform-metadata/windows-*.json` | Windows 上的 AWT / Java2D / 字体 |

| 开关 | 作用 |
|---|---|
| `headless = false` | 保留 L3 的 GUI 元数据（Compose / Skiko 必需） |
| `autoIncludeResources = true` | 注册工程资源根 |
| `detectOrphanProjectClasses = true` | 为注解处理器产物补无参构造器 |

不要先手写反射条目：先跑 `:app:cleanupGraalvmMetadata`，它会报告已被 L1–L3 或静态分析覆盖的条目。

#### 版本元数据进镜像（原生包没有 `.cfg`）

JVM 包的版本号走启动器 `app/CPPlayer.cfg` 里的 `-Dcp.player.*`；原生镜像没有启动器，只能靠 `graalvm { buildArgs }` 里的两项，缺一项就静默回落到 `BuildInfo` 的默认值：

| 构建参数 | 为什么必须有 |
|---|---|
| `-Dcp.player.versionName/versionCode/releaseChannel/gitSha` | native-image 的 `-D` **只在构建期有效**（`native-image --help` 原文）。运行期镜像里 `System.getProperties()` 只剩标准键，自定义键一个都不在 |
| `--initialize-at-build-time=cp.player.app.BuildInfo` | 把这个 object 的 `<clinit>` 挪到构建期，四个 `val` 才会随对象写进镜像堆 |

历史坑（v1.9.0 / v1.9.1）：两处都缺 ⇒ Windows 原生包「关于」页显示 `1.0.0 (1)`，更新检查也拿它当本机版本，而 Linux 的 deb 显示正常。现在 `BuildInfo` 的默认值改成 `0.0.0 (0)`，同一个断链会显示成明显不对的版本号，而不是伪装成一个像样的版本。

### 5.5 PGO

采集与训练：

| 方式 | 步骤 |
|---|---|
| CI（推荐） | 触发 `.github/workflows/desktop-pgo-instrument.yml`，CI 用 Oracle GraalVM 构建插桩版 `CPPlayer-win-x64-pgo-instrument.zip`；解压运行 `CPPlayer.exe`，走代表性场景（播放、搜索、滚动、切歌），退出后同目录生成 `default.iprof` |
| 本地 | `./gradlew :app:runWithPgoInstrument` |

分析期有 21.8 万个可达方法，GraalVM 在 `--pgo-instrument` 下强制 level 2 插桩编译。配置把并发编译线程限到 2（`-H:NumberOfThreads=2`）并留 10 GB 堆（`-J-Xmx10g`），避免 IR 图膨胀导致 Java heap space OOM。

采集到的 `default.iprof` 提交到 `app/graalvm/pgo/default.iprof`，生产发布构建（CI 与本地）自动消费并启用 `-O3`。

当前状态：profile 已入库（2026-11-14 采集，30,938,646 字节，SHA-256 `6ae1a5ec…e1f2fac`，43,195 types / 324,729 methods；其中 `cp.player` 9,061 条、`androidx.compose` 6,801 条）。采集场景与重新采集步骤见 [`app/graalvm/pgo/README.md`](../../app/graalvm/pgo/README.md)。

判断 PGO 是否生效看两处，不要只看 BUILD SUCCESSFUL：

| 判据 | 期望 |
|---|---|
| `app/build/compose/tmp/main/graalvm/nativeCompile/native-image-args.txt` | 出现 `"--pgo=<绝对路径>"`，且没有 `--pgo-instrument`（插桩版只有 `-Ob`，不能发版） |
| 构建日志 | 有 `PGO: applying recorded profile ...`；若出现 `PGO: recorded profile ... ignored`，说明工具链不是 Oracle GraalVM |

`desktop-release.yml` 的 Windows job 把这两条做成硬断言：profile 被误删或构建退化成「ML 推断版发行包」，发行 job 直接变红。

| 约束 | 内容 |
|---|---|
| 版本绑定 | profile 与 Oracle GraalVM 25.3.4.1 / 工具链 `25i3` 绑定；升级 GraalVM 必须重新采集，否则 native-image 报 profile 不兼容 |
| `.gitattributes` | `*.iprof binary` 不能删：profile 是单行 JSON，被 `text=auto eol=lf` 当文本规范化后 `--pgo` 解析不出来 |

### 5.6 托盘与通知（不用 AWT）

Nucleus 3.0 没有 AWT 窗口后端，桌面外壳也去掉了 AWT 托盘。

| 能力 | 实现 |
|---|---|
| 托盘图标 + 右键菜单 | `dev.nucleusframework:composenativetray`（`2.1.6`）；Windows 用 `Shell_NotifyIcon`，macOS 用 `NSStatusItem`，Linux 用 AppIndicator / StatusNotifierItem |
| 消息通知 | Nucleus `notification-windows`（WinRT Toast）与 `notification-linux`（freedesktop D-Bus）；macOS 故意不发 |
| 通知点击 | 携带 `MessageNotification.key`，可按会话路由与取消；托盘不再持有通知状态 |
| 已删除 | `SystemTray` / `TrayIcon` / `PopupMenu`、离屏 `TrayMenuScene`、`JWindow` 菜单弹层、`DesktopWindowPlacement` / `WindowMaximizer`、`JbrWindowChrome` |
| 窗口位置 | 改由 Tao 的 `WindowState` 负责 |
| `jdk.unsupported` | 仍留在运行时镜像：Kotlin / Skiko 运行时依赖 `sun.misc.Unsafe`，jlink 默认模块集不含它。原先的 `JbrFileDialogBridge` 已随「去 AWT」删除，但别因此删这一行——先重跑 `:app:suggestModules` 确认 |

不要重新打开 `headless = true`，也不要删 L3 的平台元数据：它由插件自动注入，覆盖 Skiko 字体回退可能触及的 Java2D / 字体类。项目自身已无任何 `java.awt` / `javax.swing` / `javax.imageio` 引用，托盘、通知与文件选择器都走原生 API；L3 只是防御性地留在镜像里。托盘与通知的 JNI 类列在 `--initialize-at-run-time=...,dev.nucleusframework.composenativetray,dev.nucleusframework.notification,...`，避免 Native Image 在构建期加载平台库。

### 5.7 文件选择器（Win32 Common Item Dialog，不用 AWT）

原生镜像里点设置页「选择目录」曾直接崩在 `sun.awt.shell.ShellFolder.<clinit>`：`ShellFolder` 先经 `Toolkit.getDefaultToolkit().getDesktopProperty("Shell.shellFolderManager")` 拿到 `Win32ShellFolderManager2`，再用 `Class.forName(..., false, null)` 动态加载它，Native Image 静态分析看不见；构造失败后回落到基类 `ShellFolderManager`，其无参构造又没登记反射，于是抛 `Could not instantiate Shell Folder Manager`。给 Swing 补 UI 元数据还会撞上第二条 `no ComponentUI class for: javax.swing.JFileChooser`。

结论不是「继续堆元数据」，而是把整条链换掉：AWT `FileDialog` 弹的是 Win95 风格旧对话框，本来也不满足 Windows 10+ 的现代体验。

| 项 | 内容 |
|---|---|
| 实现 | `Win32CommonItemDialog.kt`：FFM 调 `ole32` 的 `CoInitializeEx` / `CoCreateInstance`，按 vtable 调 `IFileDialog` |
| 现代对话框 | `CLSID_FileOpenDialog` / `CLSID_FileSaveDialog`，即 Vista+ Common Item Dialog；选目录用 `FOS_PICKFOLDERS` |
| 路径取回 | `IShellItem.GetDisplayName(SIGDN_FILESYSPATH)` + `CoTaskMemFree` |
| 统一入口 | `DesktopFileDialog`；非 Windows 回落 `zenity` / `osascript`，`PlatformFilePicker.desktop.kt` 只调它 |
| 其他 AWT 去向 | 剪贴板 → Win32 `CF_UNICODETEXT`；打开链接/目录 → `rundll32 url.dll,FileProtocolHandler` / `explorer.exe`；刷新率 → `EnumDisplaySettingsW`；壁纸取色 → Skia 解码 |
| NFD | 技术可行（Windows 后端同为 `IFileDialog`），但要多带一份 C 库、三平台构建与 `.dll/.so/.dylib` 打包，故不引入；`DesktopFileDialog` 留了替换点 |

两个已用 `-Ob` 迷你镜像 `ProbeComDialog` 验证过的坑：

- `SetFileTypes` 必须在 `SetOptions(FOS_PICKFOLDERS | ...)` **之前**调用；文件夹模式下后置会拿到 `E_INVALIDARG` (`0x80070057`)；
- `GetOptions` 读回会多出 shell 自己强制加的 `0x1000` 位，按「自己设置的位是否都在」判断，别要求完全相等。

AWT 反射 / JNI 条目已从 `app/graalvm/reachability-metadata.json` 删掉，新增 8 条 Win32 直调的 `foreign.downcalls`。完整事故记录与探针输出见 [`app/graalvm/README.md`](../../app/graalvm/README.md)。

## 6. 应用内更新链

| 步骤 | 行为 |
|---|---|
| 1 | 关于页调 GitHub Releases API |
| 2 | 按 SemVer 比较，含 prerelease 后缀 |
| 3 | Android 选 APK 资产，经 `DownloadManager` 下到 `Downloads`，完成时给通知 |
| 4 | Windows 选 `-Setup.exe`；Linux 优先 DEB、回退 TAR.GZ；Android 选 APK。选中资产的 `browser_download_url` 直接交给平台下载器 |
| 5 | Android 等 `DownloadManager` 完成；桌面把安装包流式写入 CPPlayer 管理的下载目录，不打开浏览器。校验输出非空与公开大小，有 SHA-256 digest 时一并校验。确认破坏性更新后写延迟清理标记，再打开 Android 系统安装器、启动 Windows Velopack Setup 或用系统包处理器打开 `.deb`。仍需 OS / 用户确认，不是静默安装。清理只会在目标版本或更新版本启动时发生。`.tar.gz` 回退用系统默认处理器打开（它没有原生安装器） |

### 6.1 远程公告与破坏性更新

`app-update.json` 独立从仓库 `main` 分支拉取。

| 字段 | 用途 |
|---|---|
| `announcements` | 版本区间公告：`id`、`title`、`message`、可选 `url`、`fromVersionInclusive`、`untilVersionExclusive`；记录最后关闭的 id，每个 id 只显示一次 |
| `destructiveUpdates` | 用 `fromVersionAtMost` / `toVersionAtLeast` 圈定需要警告 + 二次确认的升级 |

| 项 | 内容 |
|---|---|
| 当前策略 | 覆盖 1.8.5 及更早升级到 1.8.8 及更新（含 1.8.9） |
| 扫描对象 | 模块目录，仅对 `apiVersion` 为 1 或 3、`contract` 不是 `kmp-sdk-v1`、且 manifest id 与目录名一致的项提供清理 |
| 执行顺序 | 确认且包下载校验通过后只写 pending 标记，安装前不删任何东西；启动时由目标版本或更新版本复核 id，只删仍匹配的旧模块目录 |
| 取消 / 失败 | 旧版本不会应用标记，目录保留 |
| 永不选中 | SDK 包、账号与 Cookie 数据 |

更新流程不提供回滚包。v1.8.5 的 APK `versionCode` 可能低于后续安装，Android 会判为降级拒绝；先卸载可能清掉应用数据。本发布不会静默卸载，也不声称无数据丢失的就地回滚。

桌面构建元数据经 `app/build.gradle.kts` 的 `compose.desktop.application.jvmArgs` 传给应用（`-Dcp.player.versionName`、`.versionCode`、`.releaseChannel`、`.gitSha`），由 `BuildInfo` 读取。Gradle 属性不会自动变成 JVM 系统属性：删掉那几行，关于页会永远显示 `v1.0.0 (1)` / `unknown`，与 tag 无关。

## 7. 本地真机快发

`fastrelease` 是优化的本地签名 Android 构建，不是商店发布，无需 release keystore。

```powershell
# 构建并安装到第一台已授权 adb 设备
.\scripts\fastrelease-install.ps1 -Version 1.2.3-local -Launch

# 强制干净构建
.\scripts\fastrelease-install.ps1 -Clean -Launch
```

| 项 | 内容 |
|---|---|
| 脚本行为 | 跑 `:app-android:assembleFastrelease`，检查 `adb`，确认设备已授权，用 `adb install -r -d` 安装 |
| 前提 | 打开 USB 调试并接受授权提示 |
| 手动安装 | `app-android/build/outputs/apk/fastrelease/app-android-fastrelease.apk` |
| `versionCode` | 故意固定 `900001`：低于 `1.0.0` 推导出的 `1000000`，正式包总能替换本地包；`-d` 允许本地包盖在正式包上 |

## 8. 手动冒烟

- [ ] 推 `debug-v1.2.3` tag
- [ ] 等 Debug Release workflow，确认预发布资产
- [ ] 在旧版本打开 关于 → 检查更新
- [ ] **Windows 原生包**：装完打开 关于，版本号必须等于 tag（不是 `0.0.0 (0)`）——原生镜像的版本元数据靠 `-D` + `--initialize-at-build-time` 写进镜像，见 §5.4
- [ ] 确认弹窗显示 changelog 与下载动作
- [ ] Android 上确认系统下载通知与 `Downloads` 里的 APK
- [ ] 检查 workflow 日志里的 `CPPlayer build metadata:` 行，版本与 tag 一致

## 相关

- 构建与验证：[BUILD](BUILD.md)
- 安装包与更新在用户侧的说明：[USER_GUIDE](../USER_GUIDE.md)
- 平台构建事实速查：[AGENTS 第 6 节](../../AGENTS.md#6-平台构建事实)
