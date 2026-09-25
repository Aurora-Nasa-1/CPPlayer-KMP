# 结构迁移方案

> 本文档记录 CPPlayer-KMP 目录结构与项目规划的重整计划。
> 诊断于 2026-09-25 完成，基于当时的仓库实际状态。

## 进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | 文档与卫生（零构建风险） | ✅ 2026-09-25 |
| Phase 2 | 模块改名：`kmp-pro` → `core`、`androidApp` → `app-android`、`rootProject.name` → `CPPlayer` | ✅ 2026-09-25 |
| Phase 3 | Desktop 入口对称化 | ⏸ 可选，未做 |
| §7 | 遗留问题（坏 submodule、`.qoder/` 等） | ⏸ 待定 |

> ⚠️ **§0 与 §1 是改名前的诊断快照**，文中出现的 `kmp-pro` / `androidApp` 是当时的
> 真实名称，保留原文以便对照问题成因。当前名称见 §2。

---

## 0. 结论摘要

项目**依赖方向本身是干净的**（`androidApp → app → kmp-pro` 单向，无环、无反向引用），
问题集中在三处：

1. **文档与事实脱节** —— `README.md` 停留在「工程里只有 `kmp-pro`」的时期，
   目录树、模块列表、技术栈版本（写的是 AGP 8.7.3 / Kotlin 2.1.0，实际是
   9.1.1 / 2.4.10）全部过期。
2. **模块名不表达角色** —— `kmp-pro` 实际是后端，`app` 实际是前端，
   `androidApp` 实际是「Android 平台入口点」。三者都靠读源码才能判断分工。
3. **根目录混入非构建内容** —— Gradle 产物、Kotlin 缓存、AI 生成的 wiki、
   38 KB 死文件、两个嵌套 git 仓库。

> ❗**关于「安卓版不应该单独剥离」**：经核实，`androidApp` 独立成模块
> **不是可以改掉的设计，而是 AGP 9 的强制要求**。详见 §1。

---

## 1. 为什么 `androidApp` 不能合并进 `app`

自 AGP 9.0 起，Kotlin Multiplatform 插件不再兼容 `com.android.application`
与 `com.android.library`：

> When used along with Android Gradle plugin 9.0 or newer, the Kotlin Multiplatform
> Gradle plugin stops being compatible with the `com.android.application` and the
> `com.android.library` plugins.

官方给出的迁移动作：

> - If your Android entry point is currently implemented in a shared code module,
>   **extract it into a separate module** to avoid Gradle plugin conflicts.
> - Migrate your shared code module to use the new **Android-KMP library plugin**
>   built specifically for multiplatform projects.

来源：<https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html>

官方推荐结构 = **`androidApp`（`com.android.application`）+ 共享模块
（`com.android.kotlin.multiplatform.library`）**，也就是本工程**当前的结构**。

本工程实际上已经完成了这次迁移：`app` 与 `kmp-pro` 都使用
`com.android.kotlin.multiplatform.library`，`androidApp` 使用
`com.android.application` 且**未**引入 `kotlin.android` 插件（AGP 9 内置 Kotlin）。
`gradle.properties` 里也没有 `android.enableLegacyVariantApi=true`。

**唯一能强行合并的办法**是在 `gradle.properties` 加
`android.enableLegacyVariantApi=true` 走遗留 API。但该 API **将在 AGP 10
（约 2026 下半年）被彻底移除**，等于把问题推迟几个月再还。

**因此本方案不做合并**，改为通过**改名**让角色自解释。

### 遗留的不对称

合并不可行，但不对称仍然存在，且方向是**单向的**：

| 平台 | 入口点位置 | 是否受约束 |
|------|-----------|-----------|
| Android | `androidApp/`（独立模块） | **受 AGP 9 约束，必须独立** |
| Desktop | `app/src/desktopMain/Main.kt` | 不受约束，放在共享模块里也合法 |

也就是说：Android 侧被迫在外，Desktop 侧自愿在内。这是不对称的真实成因。
处理方式见 Phase 3（可选）。

---

## 2. 目标结构

```
CPPlayer-KMP/
├── settings.gradle.kts        rootProject.name = "CPPlayer"
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── docs/                      文档集中（本文件 + ARCHITECTURE / PROVIDER_DEV_GUIDE / RELEASE）
├── scripts/
├── native/windows-smtc/
├── reference/                 只读参考，不参与构建
│   ├── cp-player-legacy/      原 Android 项目（gitignore）
│   └── netease-module-rust/   第三方音源模块（Rust）
├── core/                      后端（11.4k 行）
├── app/                       前端（共享 UI 库 + 桌面入口）
└── app-android/               Android 入口点
```

**包名（本节写于改名之前，实际已变更 —— 见 §7.3）**：原计划保持
`cp.player.kmp.*` / `cp.player.app` 不动，理由是「包名与模块名解耦，改它会变更应用标识
导致已安装版本无法覆盖升级，收益低于成本」。用户后续决定改，实际执行为
后端包名 `cp.player.kmp` → `cp.player.core`、`applicationId` `cp.player.app` → `cp.player`。
`app-android` 的 `namespace` 仍保持 `cp.player.app`。

---

## 3. Phase 1 —— 文档与卫生 ✅ 已完成（2026-09-25）

全部为零风险改动，**不触碰任何构建逻辑**。

| 动作 | 内容 |
|------|------|
| 重写 `README.md` | 按「后端 / 前端 / 平台入口」重述模块职责，修正目录树与全部技术栈版本号 |
| 新增 `docs/ARCHITECTURE.md` | 模块职责表、依赖规则、源集分层、边界越界点清单、结构性债务 |
| 新增 `docs/RESTRUCTURE_PLAN.md` | 本文件 |
| 文档集中 | `PROVIDER_DEV_GUIDE.md` → `docs/`；`RELEASE.md` → `docs/` |
| 参考目录归位 | `API_MODULE_AND_OLD_PROJECT_REPO/` → `reference/`；`3rd-CPPlayer-netcloudMusic-Muti` → `reference/netease-module-rust`；`CPPlayer` → `reference/cp-player-legacy` |
| 清理 `.gitignore` | 删除失效规则 `CPPlayer/`、`3rd-rust-server/`；新增 `.kotlin/`、`.gradle-verify/`、`build-verify/`、`reference/cp-player-legacy/` |
| 删除死文件 | `temp_downloads_backup.kt`（38 KB，`package cp.player.ui.screen` —— 旧项目包名，全仓零引用） |

**验证**：`./gradlew projects` 正常输出三个模块；`git check-ignore` 确认新规则生效。

---

## 4. Phase 2 —— 模块改名 ✅ 已完成（2026-09-25）

**前置条件已满足**：先把工作区里 24 个已修改 + 7 个未跟踪文件验证编译通过并提交，
分成两个提交（功能工作 / 结构文档），确保改名 diff 可读。

### 4.1 改动清单（全部已执行）

`kmp-pro` → `core`（后端），`androidApp` → `app-android`（Android 入口点）。
Gradle 工程路径 `:kmp-pro` → `:core`、`:androidApp` → `:app-android`。

| # | 文件 | 改动 | 状态 |
|---|------|------|------|
| 1 | `kmp-pro/` → `core/` | `git mv`（目录） | ✅ |
| 2 | `androidApp/` → `app-android/` | `git mv`（目录） | ✅ |
| 3 | `settings.gradle.kts` | `include(":kmp-pro")` → `include(":core")`；`include(":androidApp")` → `include(":app-android")`；`rootProject.name = "KMP-PRO"` → `"CPPlayer"` | ✅ |
| 4 | `app/build.gradle.kts` | `api(project(":kmp-pro"))` → `api(project(":core"))` | ✅ |
| 5 | `.github/workflows/debug-release.yml` | 任务名 + APK 路径 | ✅ |
| 6 | `.github/workflows/release.yml` | 任务名 + APK 路径 | ✅ |
| 7 | `.github/workflows/desktop-release.yml` | `:app:${matrix.task}` 不变（`app` 不改名） | — |
| 8 | `scripts/fastrelease-install.ps1` | 任务名 + APK 路径 | ✅ |
| 9 | `scripts/release.ps1` | 任务名 | ✅ |
| 10 | `docs/RELEASE.md` | `androidApp` 路径与任务名 | ✅ |
| 11 | `README.md` | 模块表格、目录树、依赖链、构建命令 | ✅ |
| 12 | `docs/ARCHITECTURE.md` | 全文模块名 + 边界现状修正 | ✅ |
| 13 | `docs/PROVIDER_DEV_GUIDE.md` | 4 处 `KMP-PRO` → `CPPlayer` | ✅ |
| 14 | `app/src/commonMain/.../ui/screen/AboutScreen.kt:156` | 用户可见文案 `"KMP-PRO · Compose Multiplatform"` | ⏸ **未改**，属产品文案，待确认 |

### 4.2 改名时会连带变化的产物路径（实测确认，非推测）

AGP 的 APK 文件名以 **Gradle 工程名**为准，所以模块改名会让产物文件名一起变。
这一条最容易漏，因为 CI 和本地脚本里是硬编码路径：

| 构建类型 | 改名前 | 改名后 |
|----------|--------|--------|
| debug | `androidApp-debug.apk` | `app-android-debug.apk` |
| fastrelease | `androidApp-fastrelease.apk` | `app-android-fastrelease.apk` |
| release | `androidApp-release.apk` | `app-android-release.apk` |

debug 与 fastrelease 两个名字已实际构建确认。

**已按用户要求变更的**（见 §7）：`applicationId` 由 `cp.player.app` 改为 `cp.player`；
后端包名由 `cp.player.kmp` 改为 `cp.player.core`；桌面数据目录由 `~/.kmp-pro` 改为 `~/.cpplayer`（带一次性迁移）。

`app-android` 的 `namespace`（`cp.player.app`）保持不动 —— 它只影响 R 类与资源符号，
改它会连带变更 `BuildConfig` 所在包与资源引用路径，收益为零。

### 4.3 验证

```bash
./gradlew projects                    # Root project 'CPPlayer' + :app / :app-android / :core
./gradlew :core:compileKotlinDesktop
./gradlew :core:compileAndroidMain
./gradlew :core:desktopTest           # 回归测试
./gradlew :app:compileKotlinDesktop
./gradlew :app:compileAndroidMain
./gradlew :app:desktopTest
./gradlew :app-android:assembleDebug
```

> 环境提示：仓库根 `.gradle/9.4.1/fileHashes/fileHashes.lock` 常被并行会话的
> Gradle 守护进程占用，可加 `--project-cache-dir=.gradle-verify` 绕开。
> 看测试结论**直接读** `**/test-results/**/TEST-*.xml`，不要用 `| head` 截 Gradle 输出
> （会把 `BUILD SUCCESSFUL` 与测试结论一起截掉）。

---

## 5. Phase 3 —— Desktop 入口对称化（可选，收益递减）

把桌面入口点也抽成独立模块，使两个平台完全对称：

```
app/                 纯共享 UI 库（不含任何入口点与打包配置）
app-android/         Android 入口点 + APK 打包
app-desktop/         Desktop 入口点 + MSI/Dmg/Deb 打包
```

**动作**：新建 `app-desktop/`，把 `app/src/desktopMain/.../Main.kt` 与
`app/build.gradle.kts` 里的 `compose.desktop { application { ... } }` 块迁过去；
CI 的 `:app:packageMsi` 改为 `:app-desktop:packageMsi`。

**收益**：`app` 变成纯粹的可复用库；每个平台入口自己管打包，与 AGP 9 官方
「separating entry points for any app target you might have」的逻辑一致。

**代价与风险**：
- 模块数从 3 变 4，为约 30 行的 `Main.kt` 增加一层；
- **变体解析风险**：`app` 的 JVM 目标名是 `jvm("desktop")`，普通
  `kotlin("jvm")` 模块消费它时可能匹配不到变体。**规避办法**：`app-desktop`
  也声明为 KMP 模块、只保留 `jvm("desktop")` 目标，与 `app` 对齐；
- `Main.kt` 会调用 `app` 的 `DesktopRenderTuning.applyBeforeSkikoInit()`，
  跨模块可见性需确认（当前是 `internal` 的话必须放宽）。

**建议**：先做 Phase 2 观察一段时间，若「`app` 里混着打包配置」实际造成困扰再做。

---

## 6. 明确不做的事

| 项 | 原因 |
|----|------|
| 合并 `androidApp` 进 `app` | AGP 9 不允许（§1）。强行用 `android.enableLegacyVariantApi=true` 只是把问题推迟到 AGP 10 |
| 把 `app` 改成 `frontend`、`core` 改成 `backend` | `app` 同时承载共享库与桌面入口，叫 `frontend` 会丢掉「这就是应用本体」的语义；`core` 是 KMP 社区惯例，角色写进文档即可 |
| 移动 `reference/` 到仓库外 | 参考代码与实现强相关，移出会让「对照旧项目排查」变麻烦。当前用 gitignore 隔离已足够 |

---

## 7. 已知遗留问题

### 7.1 `reference/netease-module-rust` 是**未注册的 submodule**

git 索引里它的模式是 `160000`（gitlink），但仓库根**没有 `.gitmodules`**。
后果：他人克隆后该目录**为空**，且 `git submodule` 命令直接报错。

当前按「只改名不修」处理。两种修法二选一：

```bash
# 方案 A：正式注册为 submodule（保留「独立仓库」语义）
cat > .gitmodules <<'EOF'
[submodule "reference/netease-module-rust"]
	path = reference/netease-module-rust
	url = https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti.git
EOF
git add .gitmodules

# 方案 B：去 submodule 化（当成普通源码纳入本仓库）
git rm --cached reference/netease-module-rust
rm -rf reference/netease-module-rust/.git      # 去掉嵌套仓库
git add reference/netease-module-rust
```

### 7.2 `.qoder/` 有 132 个文件已被提交

AI 生成的仓库 wiki。内容与 `docs/ARCHITECTURE.md` 重叠，且会随代码漂移而失效。
**建议**（非破坏性，文件保留在磁盘上）：

```bash
git rm -r --cached .qoder
printf '\n# AI-generated repo wiki (local only)\n.qoder/\n' >> .gitignore
```

### 7.3 后端包名已改名（`cp.player.kmp` → `cp.player.core`）

原诊断建议「不动」——理由是包名不表达角色、改名波及面大。用户决定改，故已执行。
本节的正文（§0–§6）保留原判断，改动记录如下：

| 项 | 改前 | 改后 |
|----|------|------|
| 后端包名 | `cp.player.kmp` | `cp.player.core` |
| `core` 的 `namespace` | `cp.player.kmp` | `cp.player.core` |
| `core/consumer-rules.pro` | `-keep class cp.player.kmp.**` | `-keep class cp.player.core.**` |
| `applicationId` | `cp.player.app` | `cp.player` |
| 桌面数据目录 | `~/.kmp-pro` | `~/.cpplayer`（带一次性迁移） |

#### ⚠️ 连带影响：JNI 符号名变了，已编译的模块需要重新构建

JNI 按 `Java_<包名下划线化>_<类名>_<方法名>` 查找符号。宿主类从
`cp.player.kmp.provider.JniProvider` 变成 `cp.player.core.provider.JniProvider` 之后，
导出符号前缀必须跟着从 `Java_cp_player_kmp_provider_JniProvider_` 改为
`Java_cp_player_core_provider_JniProvider_`。

**症状具有欺骗性**：`.so`/`.dll` 本身仍能被 `System.load()` 成功加载，
失败发生在**首次方法调用**时（`UnsatisfiedLinkError`），表现为
「模块显示已加载，一调用就崩」。`JniProvider.isReady()` 只检查文件存在与
`System.load`，捕获不到这种情况。

受影响的两处：
1. `reference/netease-module-rust/src/util/jni.rs` —— 三个 `#[no_mangle]` 函数名；
2. `docs/PROVIDER_DEV_GUIDE.md` §3.3 —— 第三方模块作者的契约（**已同步更新**）。

**未处理**：`jni.rs` 仍是旧前缀。是否连带修改参考仓库，取决于是否还有用旧前缀
编译的模块需要继续兼容 —— 见 §8。

### 7.4 其它

| 项 | 说明 |
|----|------|
| `ui/component/`（21 文件）与 `ui/components/CommonComponents.kt`（1 文件） | 命名易混，建议把 `CommonComponents.kt` 并入 `ui/component/` |
| `PlaybackEngine` / `PlaybackState` / `NoopPlaybackEngine` | 与 `PlatformPlayer` / `PlatformPlaybackState` 平行的另一套抽象，全仓无实际使用 |
| `CachedMusicApiService.callApiCached` | 全仓无调用方，缓存层目前是空转 |
| `PlaybackControllerImpl.playCurrent(skipIfSame)` | 参数从未被使用 |
| `native/windows-smtc/` | 只有一个 README，无代码 |
| `core` 的 `commonMain` 依赖 `composemediaplayer-audio` | 名字带 Compose，易被误认为后端依赖 UI。实际提供的是 rodio 音频播放能力，与 Compose UI 无关 |
| `AboutScreen.kt` 里的可见文案 `KMP-PRO · Compose Multiplatform` | 属于产品文案，改名与否由产品决定，未随本次技术改名调整 |

---

## 8. 待决策：JNI 符号兼容策略

后端包名改名（§7.3）破坏了已编译 JNI 模块的符号匹配。三种处理方式：

| 方案 | 做法 | 代价 |
|------|------|------|
| A. 只更新源码（推荐） | 同步改 `jni.rs` 的三个符号名，要求模块重新编译后分发 | 用旧前缀编译的模块全部失效，需重新分发 |
| B. 双符号兼容 | `jni.rs` 里保留旧前缀函数作为转发壳，新旧符号都导出 | 参考仓库里多 3 个转发函数；`JniProvider` 换包时又得再来一次 |
| C. 固定宿主类名 | 把 `JniProvider` 钉在 `cp.player.kmp.provider` 不动，只改其它类的包名 | 包结构出现一个「例外」，长期维护者容易困惑 |

当前状态：**未处理**，等待决定。在决定之前，`jni.rs` 与宿主代码是不一致的。
