# Linux 通用分发包（tar.gz）与 AUR 自动发布

> 2026-10-04 落地。涉及文件：
> [`app/build.gradle.kts`](../../app/build.gradle.kts)（`packageLinuxTarGz` 任务）、
> [`packaging/aur/cpplayer-bin/PKGBUILD`](../../packaging/aur/cpplayer-bin/PKGBUILD)、
> [`.github/workflows/desktop-release.yml`](../../.github/workflows/desktop-release.yml)
> （Linux 腿 + `aur` job）。

## 1. 产物矩阵

| 产物 | 任务 | 说明 |
|------|------|------|
| `.deb` | `:app:packageDeb` | Debian 系（原有） |
| **`.tar.gz`** | `:app:packageLinuxTarGz` | **通用 Linux，解压即用**（本页新增） |
| AUR `cpplayer-bin` | workflow `aur` job | stable 渠道自动发布（本页新增） |

tar.gz = jpackage 的 app-image（`createDistributable` 产物）直接压缩：

```
CPPlayer/
├── bin/CPPlayer              启动器（自带 jlink 裁剪的 JBR 运行时）
├── lib/CPPlayer.png 等       图标 / 配置
└── lib/runtime/...           JetBrains Runtime（原生窗口拖动同 Windows 包）
```

- **为什么不是 AppImage**：AppImage 需要额外下载 appimagetool、产物行为也和普通
  目录不同；tar.gz 解压即用，任何发行版都能跑，也是 AUR `-bin` 包的标准源。
- **解压后运行**：`./CPPlayer/bin/CPPlayer`（启动器按相对路径找运行时，放哪都行）。
- 本地构建（**必须在 Linux 上**）：

  ```bash
  ./gradlew :app:packageLinuxTarGz -Pcp.jbrDownload=true   # 首次会下载 JBR
  # 产物：app/build/compose/binaries/main/targz/CPPlayer-<version>-linux-x64.tar.gz
  ```

- 在 Windows/macOS 上运行该任务会**立即报错**（不会先白跑 jpackage）：
  createDistributable 产出的是当前宿主平台的应用镜像，打成 Linux 包是错的。
  刻意不做静默跳过 —— 那是 SKIPPED + BUILD SUCCESSFUL 的假成功。
- 权限位（启动器/`bin/java`/`jspawnhelper` 的 755）靠「源目录 → tar」在 POSIX
  文件系统上原样保留，任务里**不要**加 `fileMode`；CI 的布局断言 +
  PKGBUILD 里的 `chmod` 兜底是双保险。

## 2. AUR 包（cpplayer-bin）是怎么发的

CI 链路（`desktop-release.yml`，仅 **stable** 渠道，debug 预发布不进 AUR）：

```
meta → desktop(Linux 腿: deb + tar.gz) → publish(挂到 GitHub Release)
                                            └→ aur job:
   1. archlinux:base-devel 容器（要 makepkg --printsrcinfo，ubuntu 没有）
   2. 流式下载刚发布的 tar.gz，算 sha256
   3. clone ssh://aur@aur.archlinux.org/cpplayer-bin.git
      （包不存在时 AUR 给空仓库 → 首次发布也是全自动）
   4. 拷入本仓库的 PKGBUILD 模板，sed 重写 pkgver/pkgrel/url/source/sha256sums 五行
   5. makepkg --printsrcinfo > .SRCINFO；commit；push origin HEAD:master
```

- 包布局：整体装 `/opt/CPPlayer`，`/usr/bin/cpplayer` 是跳板脚本
  （`exec /opt/CPPlayer/bin/CPPlayer "$@"`，不用软链 —— jpackage 启动器按自身
  真实路径找 `../lib/<名>.cfg`）。
- 依赖：`alsa-lib`（rodio/CPAL 播放）+ `fontconfig` + X11 组（AWT/Skiko）。
  应用自带 JBR，不需要任何 `java-runtime`。
- `pkgver` 会剥掉预发布后缀（AUR 只允许 `[A-Za-z0-9._]`）。
- **改包元数据**（依赖、desktop 条目、描述）：直接改本仓库的
  `packaging/aur/cpplayer-bin/PKGBUILD` 模板，CI 原样带上去。
  只有那五行是 CI 生成的。
- `license=('LicenseRef-UNLICENSED')` 是占位：仓库还没有 LICENSE 文件，
  加了之后记得改成对应 SPDX 标识。

## 3. GitHub 配置步骤（一次性）

### 3.1 注册 AUR 账号并挂 SSH 公钥

1. 在 [aur.archlinux.org](https://aur.archlinux.org) 注册账号。
2. 生成**专用**密钥对（别复用已有密钥，方便单独吊销）：

   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/aur -C "cpplayer-aur"
   ```

3. 打开 AUR 「My Account」页面，把 **公钥**（`~/.ssh/aur.pub` 内容）粘进
   **SSH Public Key** 字段保存。

### 3.2 把私钥配置到 GitHub

仓库 → **Settings → Secrets and variables → Actions → New repository secret**：

| Name | Value |
|------|-------|
| `AUR_SSH_PRIVATE_KEY` | `~/.ssh/aur`（**私钥**）的完整内容，含首尾行 |

就这一个 secret。tar.gz 与 Windows 安装包 / deb 的发布**不需要任何配置**；未配置该 secret 时
`aur` job 会打 notice 并跳过，不影响 release。

### 3.3 验证

```bash
ssh -i ~/.ssh/aur aur@aur.archlinux.org
# 首次连接确认 host key；成功标志是 AUR 的问候横幅而不是 permission denied
```

### 3.4 发一次版走全流程

```bash
git tag v1.2.3 && git push origin v1.2.3
```

- desktop-release.yml：Windows Setup.exe（Velopack）+ Linux deb + tar.gz 挂到 GitHub Release；
- aur job：更新 AUR 上的 cpplayer-bin（首次推送会直接建包）；
- release.yml：Android APK 挂到同一个 Release。

之后在 Arch 上：`paru -S cpplayer-bin`（或 `yay`）即可安装。

## 4. 已核对过的依据（防回退）

- compose 插件 1.12.1 源码：`createDistributable` = jpackage `--type app-image
  --dest build/compose/binaries/main/app`，产物目录名 = `packageName`；
  `TargetFormat.AppImage.outputDirName == "app"`。
- Tar 顶层前缀：Gradle `from(dir){into(name)}` 会垫回 `CPPlayer/`（已用
  scratch 工程实测归档条目为 `CPPlayer/bin/CPPlayer` 等）。
- AUR 规则（Arch Wiki「AUR submission guidelines」）：clone 不存在的包名得到
  空仓库（首次建包无需手工操作）；push 只认 `master`；`PKGBUILD` 与 `.SRCINFO`
  必须同 commit；提交身份来自 git config。
- Windows 本地任务守卫已实测：`BUILD FAILED` + 清晰错误信息，jpackage 未执行。
- 布局断言的写法（2026-10-05 修正）：**不能**写成 `tar -tzf "$f" | grep -qx ...` ——
  `set -o pipefail` 下 `grep -q` 命中即退出、关掉管道读端，而 tar 的清单有 265 KiB+
  （内置 JBR 几千条，远超 64 KiB 管道缓冲）⇒ tar 被 SIGPIPE 杀死（`PIPESTATUS=141`），
  流水线被判失败 ⇒ **文件在不在都红**，报的却是「缺启动器」。现在先把清单读进变量再
  grep。改这一步时**别再改回管道**。
