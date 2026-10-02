# CPPlayer

> 跨平台音乐播放器（Kotlin Multiplatform）。Android 与 Desktop（JVM）共用一套
> 播放内核、音源插件系统与 Compose UI。
>
> 本仓库是原 Android 项目 `CPPlayer` 的 KMP 移植版。旧项目与第三方音源模块源码
> 作为**只读参考**保留在 `reference/` 下，不参与构建。

**文档入口**：[用户使用指南](docs/USER_GUIDE.md)（安装 / 音源 / 账号 / 推送 / FAQ）
· [开发者文档索引](docs/README.md)（架构 / Provider 开发 / 集成契约 / 发布）

---

## 模块划分

工程由三个 Gradle 模块组成，职责按「后端 / 前端 / 平台入口」划分：

| 模块 | 角色 | 规模 | 说明 |
|------|------|------|------|
| `core` | **后端** | 94 文件 / 11.4k 行 | 音源插件系统、音乐 API、缓存、播放内核、下载、本地媒体扫描、本地流输出服务。**不含任何 UI 代码** |
| `app` | **前端** | 88 文件 / 16.6k 行 | Compose Multiplatform UI、Voyager 导航、ScreenModel、更新检查。另有桌面端入口 `Main.kt` |
| `app-android` | **安卓入口点** | 4 文件 / 236 行 | `MainActivity`、`Application`、Media3 `MediaSessionService`、Manifest |

依赖方向是单向的，不允许反向或跨层引用：

```
app-android ──▶ app ──▶ core
```

前端访问后端的**唯一入口**是 `cp.player.core.MusicBackend`。Provider / Module / API
等内部组件不应被前端直接触碰 —— 详见 [`docs/dev/ARCHITECTURE.md`](docs/dev/ARCHITECTURE.md)
（含当前越界点的清单）。

> **后端包名已从 `cp.player.kmp` 改为 `cp.player.core`**（与模块名对齐）。
> 这会让 JNI 导出符号前缀同步变化，用旧前缀编译的音源模块需要重新构建 ——
> 详见 [`docs/history/RESTRUCTURE_PLAN.md`](docs/history/RESTRUCTURE_PLAN.md) §7.3。

### 为什么安卓入口是独立模块

这不是历史遗留，而是 **AGP 9 的硬性要求**。自 AGP 9.0 起，Kotlin Multiplatform
插件**不再兼容** `com.android.application` / `com.android.library`：

> When used along with Android Gradle plugin 9.0 or newer, the Kotlin Multiplatform
> Gradle plugin stops being compatible with the `com.android.application` and the
> `com.android.library` plugins.
> —— [kotlinlang.org · AGP 9 迁移指南](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)

官方给出的迁移动作是「把 Android 入口点抽到独立模块」，推荐结构正是
**独立的应用模块（应用插件）+ 共享模块（Android-KMP library 插件）**。
本工程已经采用了这套结构（`app` 与 `core` 均使用
`com.android.kotlin.multiplatform.library`）。

**所以 `app-android` 不能合并进 `app`** —— 在 AGP 10 移除遗留 API 之前，
唯一能这么做的办法是设置 `android.enableLegacyVariantApi=true`，那只是把问题
推迟到 2026 下半年。

---

## 目录结构

```
CPPlayer-KMP/
├── settings.gradle.kts            # include(":core") / (":app") / (":app-android")
├── build.gradle.kts               # 根：插件声明（全部 apply false）
├── gradle.properties              # 版本号唯一来源：app.versionName / versionCode / releaseChannel
├── gradle/libs.versions.toml      # 版本目录
├── docs/
│   ├── README.md                  # 文档导航（用户 / 开发者 / 历史归档三档）
│   ├── USER_GUIDE.md              # 用户使用指南
│   ├── dev/                       # 开发者文档：架构、Provider 指南、集成契约、发布、JBR 打包
│   └── history/                   # 历史方案与审计归档（内容以写作时点为基准）
├── scripts/                       # release.ps1 / fastrelease-install.ps1
├── native/windows-smtc/           # 决策记录：SMTC 已由 JMTC 实现，此处方案已废弃（无代码）
├── reference/                     # 只读参考，不参与构建
│   ├── cp-player-legacy/          # 原 Android 项目（本地 checkout，已 gitignore）
│   └── netease-module-rust/       # 第三方音源模块（Rust：api / server / util）
├── core/                          # 后端
│   └── src/{commonMain,jvmMain,androidMain,desktopMain,desktopTest}
├── app/                           # 前端（共享 UI 库 + 桌面入口）
│   └── src/{commonMain,androidMain,desktopMain,desktopTest}
└── app-android/                   # 安卓入口点
    └── src/main/{kotlin,res,AndroidManifest.xml}
```

### 源集分层

`core` 采用四层源集，`app` 采用三层：

```
commonMain  ──▶  jvmMain  ──▶  { androidMain, desktopMain }
```

| 源集 | 放什么 |
|------|--------|
| `commonMain` | 纯跨平台代码：模型、Provider 抽象、Ktor 客户端、缓存、播放控制 |
| `jvmMain` | Android 与 Desktop 共享的 JVM 实现：Socket / Zip / ELF / 二进制 Provider / 本地流输出服务 |
| `androidMain` | Android 独有：`Context`、`SharedPreferences`、`Build.SUPPORTED_ABIS`、Media3 播放器、JNI Provider |
| `desktopMain` | Desktop 独有：`~/.cpplayer` 持久化（运行时配置目录，首次启动自动从旧名 `.kmp-pro` 迁移）、rodio 播放器、JMTC 媒体控制、Skiko 渲染调优 |

---

## 构建

```bash
# 后端（两个平台各编译一次）
./gradlew :core:compileKotlinDesktop
./gradlew :core:compileAndroidMain

# 后端单元测试（回归测试所在）
./gradlew :core:desktopTest

# 前端
./gradlew :app:compileKotlinDesktop
./gradlew :app:compileAndroidMain
./gradlew :app:desktopTest

# 产物
./gradlew :app:run              # 桌面端直接运行
./gradlew :app:packageMsi       # Windows 安装包（另有 Dmg / Deb）
./gradlew :app-android:assembleDebug
```

技术栈：Gradle 9.4.1 / Kotlin 2.4.10 / AGP 9.1.1 / Compose Multiplatform 1.11.1 /
Ktor 3.0.3 / kotlinx-serialization 1.7.3 / coroutines 1.9.0 / datetime 0.6.1 /
Media3 1.4.1。版本号唯一来源是 `gradle/libs.versions.toml`。

发布流程见 [`docs/dev/RELEASE.md`](docs/dev/RELEASE.md)，桌面端打包运行时（JBR）见
[`docs/dev/JBR_PACKAGING.md`](docs/dev/JBR_PACKAGING.md)。

---

## 核心设计

### 后端统一入口：`MusicBackend`

`core/src/commonMain/.../MusicBackend.kt` 是前端的唯一依赖类型，负责：

1. **生命周期 + 状态机** —— 通过 `stateFlow` 暴露 `BackendState`，自动处理初始化、
   Provider 激活与错误恢复；
2. **Provider 管理** —— 导入 / 切换 / 删除音源模块，导入时自动激活首个 Provider；
3. **音乐数据访问** —— 通过 `musicApi` / `cachedApi` 提供**带缓存**的云音乐 API；
4. **播放控制** —— 队列、seek、切歌、歌词、音质。

### 缓存层：`CachedMusicApiService`

在 `MusicApiServiceImpl` 之上再封装一层。它**实现同一个 `MusicApiService` 接口**，
所以对调用方是透明的 —— `MusicBackend.musicApi` 交出去的就是它，裸实现不外泄。

**主路径：读透（read-through）**，覆写了 `isCacheable(...)` 名单内的读类方法：

```
1) 命中且未超过 freshTtlMs  → 直接返回缓存，不发网络请求
2) 未命中 / 已过期          → 回源；成功则写回缓存并返回
3) 回源抛异常 / 判为 ERROR  → 多 Provider 容灾 → 旧缓存（哪怕过期）→ 原样交出失败响应
```

**副路径：流式 `callApiCached(...)`**，需要"先渲染缓存、后台刷新"时用，
返回 `Flow<CacheResult<JsonElement>>`，多值发射：

```
1) 先返回缓存          → CacheResult.Cached(data, isStale)        （即时）
2) 后台拉取网络        → delegate.callApi(...)
3) 计算响应指纹        → Fingerprinter.compute(json)
4) 指纹相同            → CacheResult.NoChange                     （内容未变）
   指纹不同            → CacheResult.Fresh(data)                  （异步回传 + 写回缓存）
5) 响应判为 ERROR      → 多 Provider 容灾 tryFallback(...)
6) 网络异常            → CacheResult.Error(message, fallback = 缓存)
```

- **指纹**（`Fingerprinter`）：抽取 `code` + 顶层数组长度 + 主数据数组的 `id` 列表
  （前 64 个，去重排序）+ 版本位。增删条目指纹变化、**重排不算变化**，改无关字段不影响。
- **缓存键**：`providerId#method#sortedParams#cookieHash`，默认 `InMemoryApiCache`
  （LRU，容量取 `CacheConfig.maxEntries`）。**cookie 参与键** —— 同机多账号必须隔离，
  否则 B 账号会读到 A 账号的歌单。
- **写/动作类接口不缓存**（登录、点赞、发评论、打卡等），见 `isCacheable(...)`。
- **写操作会失效对应读缓存**：`addTracksToPlaylist` → `playlist/track/all` + `playlist/detail`，
  `likeSong` → `user/like/list`，`postComment` / `likeComment` → 该 `type` 对应的评论端点，
  `logout` 清全表。新增写接口时要同步补映射。
- **可观测**：`backend.cachedApi.stats`（hits / misses / stores / staleServed / invalidated）。
- **关缓存**：`CacheConfig(enableCache = false)`。

### 三级健康分类

| 级别 | 含义 | 处理 |
|------|------|------|
| `OK` | 响应正常 | 直接使用 |
| `WARNING` | 不符合预期但勉强可用（缺可选字段、慢响应、空数据、异常 code） | 使用但附 `warnings` 告警 |
| `ERROR` | 不可用（解析失败、Provider 不支持 code=-1、`MALFORMED_RESPONSE`） | 触发多 Provider 错误回退；失败则带缓存降级 |

`overallLevelFlow` 反映最近 100 条记录的综合等级，供 UI 顶部状态指示。

### 本地流输出 + 外部推送

CPPlayer 可作为**推送方**，把本地转码后的 HTTP 流推给外部接收端（游戏 radio 一类）：

- `8080` 是 CPPlayer 自己开的流输出端口（Ktor CIO，字节直通，透传
  `Content-Length` / `Content-Range` / `Accept-Ranges`）；
- `8420` 是**接收端**的端口，通过 `/api/v1/play-url` 等端点接收推送；
- 输出模式可选「本机声卡」或「只做服务器」（静默模式，本机音量恒定为 0）。

配置见 `cp.player.core.control.LocalServerConfig`，UI 入口在设置页「本地服务器」。

---

## 参考代码

`reference/` 下的两份源码**仅供查阅，不参与构建**，改动它们不会影响产物：

- `reference/cp-player-legacy/` —— 原 Android 项目（Kotlin + Media3 + Rust 音频引擎）。
  移植时的对照基准，尤其用于核对 seek / 切歌 / 本地文件打开等行为差异。
- `reference/netease-module-rust/` —— 第三方音源模块，`src/api/` 下有 400+ 个
  网易云 API 实现，`src/server/` 与 `src/util/` 提供本地 HTTP 服务与 JNI 入口。

> 移植过程与差异说明见 `reference/cp-player-legacy/README.md` 及本文件历史版本。
