# Windows 安装包：Velopack（取代 jpackage 的 MSI）

> 2026-10-05 落地。涉及文件：
> [`app/build.gradle.kts`](../../app/build.gradle.kts)（`packageWindowsVelopack` 任务），
> [`.github/workflows/desktop-release.yml`](../../.github/workflows/desktop-release.yml)
> （Windows 腿），
> [`AppUpdateChecker.kt`](../../app/src/commonMain/kotlin/cp/player/app/update/AppUpdateChecker.kt)
> （Windows 资产匹配）。
>
> Linux 侧（deb + tar.gz + AUR）不受影响，见 [`LINUX_PACKAGING.md`](LINUX_PACKAGING.md)。

---

## 1. 为什么换掉 jpackage 的 MSI

三个毛病——**装新版不继承上次的安装目录**、**目录选择框是老式对话框**、**向导步骤多**——
看似是配置问题，其实不是：

| 现象 | 真实原因 | 能不能配 |
|------|----------|----------|
| 升级不继承安装目录 | jpackage 的 MSI 不写 `ARPINSTALLLOCATION`；要改得覆盖内置 `main.wxs` | 不能：`--resource-dir` 未对外暴露，Compose 插件也没透出 |
| 目录选择框复古 | WiX 默认目录浏览对话框 | 同上 |
| 向导步骤多 | MSI 的 InstallUISequence | 同上 |
| `description` 有中文就 `exited with 311 code` | MSI 数据库 codepage 被内置 `MsiInstallerStrings_en.wxl` 钉死在 1252 | 不能（`--win-codepage` 至今没有，JDK-8290471） |

结论：**jpackage 是「MSI 生成器」，不是现代安装器**。它只暴露 WiX 模板允许的那几个开关
（`--win-menu` / `--win-dir-chooser` / `--win-per-user-install` …），模板本身动不了。
所以正确的做法不是调参，而是**让 jpackage 退回到只出 app-image，把打包交给专门的安装器**。

选 [Velopack](https://velopack.io)：Squirrel.Windows 的官方继任者，Rust 实现、**语言无关**
（官方把 Java 列为一等支持），一条命令出安装包 + 更新源 + delta 差分。

它的解法是**取消问题**而不是修好它：

- 不设目录选择页，直接装到 `%LOCALAPPDATA%\CPPlayer` —— 于是「继承上次目录」这个问题
  不再存在（升级就是把 `app-<version>/` 换掉，用户数据本来就在 `~/.cpplayer`）；
- 双击 `Setup.exe` → 解压 → 应用自动打开，无向导、无 UAC；
- 升级只下 delta 差分，由 `Update.exe` 静默替换，用户不再走一遍安装。

---

## 2. 产物矩阵

输出目录：`app/build/compose/binaries/main/velopack/`

| 产物 | 作用 |
|------|------|
| `CPPlayer-<version>-win-Setup.exe` | 用户下载的东西：双击即装完并启动 |
| `CPPlayer-<version>-win-full.nupkg` | 全量更新包 |
| `CPPlayer-<version>-win-delta.nupkg` | 相对上一版的差分更新包（JBR runtime ~80MB，这个很值） |
| `RELEASES` | 更新清单，应用内更新源读它 |
| `CPPlayer-<version>-win-portable.zip` | 解压即用，与 Linux 的 tar.gz 对称 |

全部作为 release 资产上传——`RELEASES` 与 nupkg **必须**在同一个 release 里，
否则更新源不可用。

安装后的布局（Velopack 管，别在代码里猜路径）：

```
%LOCALAPPDATA%\CPPlayer\
  Update.exe          更新器
  app-<version>\      每版一个目录（快捷方式指向当前版本）
  packages\           已下载的更新包
```

---

## 3. 构建

`vpk` 是 .NET global tool，**本机要有 .NET SDK**（只装 runtime 不行，
`dotnet tool install` 会报 "No .NET SDKs were found"）。

```powershell
dotnet tool install --tool-path .tools vpk
./gradlew :app:packageWindowsVelopack -Pcp.vpkPath=".tools/vpk.exe"
```

CI 的 windows runner 自带 SDK，工作流里已经装好并把绝对路径传进来：

```yaml
-Pcp.vpkPath="${{ github.workspace }}/.tools/vpk.exe"
```

⚠️ 这里**必须**用 `${{ github.workspace }}`（`D:\a\...`），不能写 `$PWD/.tools/vpk.exe`：
Git Bash 的 `$PWD` 是 `/d/a/...`，Java 在 Windows 上解析不了这种路径。

任务内部做了三道拦截，都不会「静默成功」：

1. 非 Windows 宿主 → 直接报错（不会先白打一遍 jpackage）；
2. `cp.vpkPath` 指向的文件不存在 → 报错并给出安装命令；
3. `createDistributable` 产物布局不对（缺 `CPPlayer.exe` 或 `app/` / `runtime/`）→ 报错。

打包完还会校验 `Setup.exe` 与 `RELEASES` 确实产出了。

`vpk pack` 的关键参数（为什么这么传，见 `app/build.gradle.kts` 里的注释）：

| 参数 | 值 | 理由 |
|------|----|----|
| `--shortcuts` | `Desktop,StartMenu` | 开始菜单快捷方式放进 `CPPlayer\` 子目录，与 `WindowsSmtcIdentity` 自己写的 `Programs\CPPlayer.lnk` **错开**——同名文件互相覆盖会丢 AUMID，SMTC 面板会退回「未知应用」 |
| `--skipVeloAppCheck` | — | Velopack 默认要求应用在启动早期调用自家的 `VelopackApp` builder（安装/更新回调）。JVM 侧不引它的 SDK，更新由 `Update.exe` 命令行驱动，不跳过会直接报错 |
| `--mainExe` | `CPPlayer.exe` | jpackage app-image 里的启动器名（**只要文件名，不要路径**） |

---

## 4. 更新链路

**现状**（本次未改）：`AppUpdateChecker` 在 Windows 上匹配 `-Setup.exe`，然后打开浏览器/
下载，安装仍由用户手动完成。相比 MSI 时代，安装这一步已经从「向导 + 选目录」变成
「双击一下」，但还不是自动更新。

**下一步（待做）**：应用内静默更新。Velopack 的更新源可以直接用 GitHub Releases
（`vpk` 会生成 `RELEASES`，客户端按它找包）。JVM 侧不需要引 Velopack 的 SDK：

1. 从 release 资产里取 `RELEASES`，按当前版本挑 `full` / `delta` 包；
2. 下载 nupkg 到临时目录（Velopack 自带 SHA1/SHA256 校验清单，可直接比对）；
3. `ProcessBuilder("<安装目录>/Update.exe", "apply", "-p", "<nupkg>", "--waitPid", "<当前pid>")`
   然后自己退出——`Update.exe` 会等父进程结束后替换文件并重启应用。

> 因为用了 `--skipVeloAppCheck`，**安装后/更新后的回调钩子是没有的**。如果将来需要
> 「更新完之后弹一次 changelog」这类行为，得自己在 `sq.version` / 本地标记里做幂等判断。

---

## 5. 已知项与待办

1. **旧 MSI 用户会并存两份。** Velopack 的 `packId` 与 MSI 的 `upgradeUuid` 是两套体系，
   装新版检测不到旧 MSI 安装。要么在发布说明里让用户先卸载旧版，要么在首启时查
   `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall` 提示一次。**未做。**
2. **应用内静默更新未接**（见 §4）。
3. **代码签名**：没有签名的 `Setup.exe` 会吃 SmartScreen 警告。MSI 时代同样没签，
   Velopack 有 `--signParams` / `--azureTrustedSignFile`，有证书时可随时接上。
4. **`description` 的 ASCII 硬约束已降级为警告** —— 它原本只为 MSI 存在，现在只剩
   deb/dmg 消费它。
5. **待核实**：CI 里 `-Pcp.jbrHome="$PWD/.jbr/windows-x64"` 传的是 Git Bash 的 MSYS 路径
   （`/d/a/...`），Java 在 Windows 上未必解析得了 —— 若解析失败会**静默用 temurin 打运行时**。
   判据是 CI 日志里有没有 `CPPlayer: JBR runtime = ...` 那一行（没有就是没换上）。
   与本次改动无关，但值得单独查一次。
