# 架构与模块职责

本文档定义 CPPlayer 的模块边界与依赖规则。**新增代码前先读这一页**，
它说明了什么东西该放在哪个模块、以及谁可以依赖谁。

---

## 1. 三个模块的角色

```
        ┌──────────────────────────────────────────────┐
        │  androidApp/          安卓入口壳（236 行）     │
        │  MainActivity · Application · MediaSession   │
        └───────────────────┬──────────────────────────┘
                            │ depends on
        ┌───────────────────▼──────────────────────────┐
        │  app/                 前端（16.6k 行）        │
        │  Compose UI · Voyager · ScreenModel          │
        │  更新检查 · 平台入口（Android / Desktop）      │
        └───────────────────┬──────────────────────────┘
                            │ depends on
        ┌───────────────────▼──────────────────────────┐
        │  kmp-pro/             后端（11.4k 行）        │
        │  Provider · API · 缓存 · 播放内核 · 下载       │
        │  本地媒体扫描 · 本地流输出服务                 │
        └──────────────────────────────────────────────┘
```

### `kmp-pro` —— 后端

音乐领域能力的全部实现。**规则：这个模块里不允许出现 UI 代码**
（Compose 可组合函数、Screen、ScreenModel、导航）。

| 包 | 职责 |
|----|------|
| `api/` | 云音乐 API 方法常量与 `MusicApiService` 实现、Provider 容灾 |
| `provider/` | 音源插件系统：`BackendProvider`、`ModuleManifest`、`ProviderManager`、`ModuleManager`，以及 Http / Binary / JNI 三类 Provider 加载方式 |
| `cache/` | `CachedMusicApiService` + `Fingerprinter` + `ApiCache` |
| `playback/` | `PlaybackController`（队列 / seek / 切歌 / 歌词）、`PlatformPlayer` 抽象、`SeekSettle`、`SilentOutputPlayer` |
| `control/` | 本地流输出服务与外部推送（`LocalServer`、`ExternalPusher`） |
| `download/` | `MediaDownloadManager` |
| `local/` | 本地媒体扫描（`LocalMediaSource`、`ScanProgress`） |
| `media/` | `LocalMediaItem`、`MediaType` 等媒体描述 |
| `music/` | 领域模型：`TrackSummary`、`PlaylistSummary`、`CPMediaId`、`MusicResult` |
| `monitor/` | `HealthMonitor` 三级健康分类 |
| `model/` | 下载任务、歌词等数据传输对象 |
| `util/` | `PlatformContext`、`SettingsStorage` 等平台抽象 |
| `MusicBackend.kt` | **后端统一入口**（见 §2） |

### `app` —— 前端

Compose UI 与平台入口。**规则：只通过 `MusicBackend` 访问后端能力。**

| 目录 | 职责 |
|------|------|
| `App.kt` / `AppModel.kt` | 顶层编排与状态聚合（组合根，见 §3） |
| `ui/screen/` | 各页面 |
| `ui/component/` | 可复用组件（21 个） |
| `ui/model/` | Voyager `ScreenModel` |
| `ui/theme/` | 主题 |
| `repository/` | `MusicRepository` / `AuthRepository` —— 对 UI 友好的数据访问封装 |
| `platform/` | `expect` / `actual` 平台能力（媒体控制、平台动作、渲染调优） |
| `update/` / `version/` | 更新检查与版本比较 |

### `androidApp` —— 安卓入口壳

只放「安卓进程启动所需、且不属于 UI」的东西：`MainActivity`、
`CPPlayerApplication`、Media3 `MediaSessionService`、`ControllerForwardingPlayer`、
Manifest 与 `res/xml/`。**不放业务逻辑。**

---

## 2. 唯一入口：`MusicBackend`

`kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt`

> CPPlayer 后端统一入口（KMP 版）。
> **前端唯一依赖的后端类型。** 所有音乐数据访问、Provider 管理、播放控制
> 都应通过此对象进行，禁止直接触碰 `ProviderManager` / `ModuleManager` 等内部组件。
> —— 摘自该文件的 KDoc

它对外提供：`stateFlow` / `init` / `musicApi` / `cachedMusicApi` /
`playback` / `applyOutputConfig` / Provider 导入切换删除 / 推送与本地服务器控制。

---

## 3. 边界现状（**需要收敛的部分**）

门面存在，但边界目前靠约定而非结构约束。实测 `app` 直接 import 了
**35 个不同的后端符号**，其中 `MusicBackend` 只出现在 3 个文件里。

### 3.1 有正当理由的越界（保持现状）

| 位置 | 触碰 | 理由 |
|------|------|------|
| `AppModel.kt` | `playback.PlaybackController`、`provider.BackendProvider`、`provider.ProviderCookieStorage`、`util.SettingsStorage`、`control.LocalServerConfigStore`、`monitor.HealthMonitor`、`api.*` | **组合根**。后端的装配与生命周期只能在这里发生 |
| `platform/*MediaControls*` | `playback.PlaybackController` | 平台媒体控制（通知栏 / SMTC / 耳机按键）必须拿到控制器实例 |
| `Main.kt`、`DesktopRenderTuning.kt` | `util.defaultSettingsStorage` | 进程启动引导，早于 `MusicBackend.init` |
| 全体 | `music.*` / `model.*` / `media.*` | 领域模型是前后端共享的数据契约，**本就该直接引用** |

### 3.2 应当收敛的越界（待办）

| 文件 | 问题 | 建议 |
|------|------|------|
| `repository/AuthRepository.kt` | 直接 import `api.MusicApiService` | 改走 `MusicBackend.musicApi` |
| `repository/MusicRepository.kt` | 同上 | 同上 |
| `ui/model/SearchScreenModel.kt` | import `api.MusicApiMethod`（API 方法常量泄漏到 UI 层） | 由 `repository/` 或 `MusicBackend` 暴露语义化方法 |
| `ui/screen/SearchScreen.kt` | 同上 | 同上 |
| `ui/screen/HealthScreen.kt` | 直接 import `monitor.HealthMonitor` | 经 `AppModel` 暴露的只读状态 |
| `ui/component/SleepTimerDialog.kt` | 直接 import `playback.PlaybackController` | 经 `AppModel` 暴露 |
| `ui/screen/PlaybackSettingsScreen.kt` | 同上 | 同上 |

**判定标准**：如果一个 `ui/` 或 `repository/` 文件需要 import
`api.` / `provider.` / `monitor.` / `control.` 下的类型，那多半是缺了一个
由 `AppModel`（或 `MusicBackend`）暴露的语义化接口。

### 3.3 后端侧的依赖泄漏

`kmp-pro` 的 `commonMain` 声明了 `composemediaplayer-audio` 依赖。
它虽然是「音频播放器」而非 UI 框架，但名字带 Compose，容易被误认为后端在依赖 UI。
**建议**：在 `docs/` 里记录它实际提供的是 rodio 音频播放能力，与 Compose UI 无关。

---

## 4. 源集分层规则

```
commonMain  ──▶  jvmMain  ──▶  { androidMain, desktopMain }
```

| 源集 | 放什么 | 不该放什么 |
|------|--------|-----------|
| `commonMain` | 纯跨平台逻辑、`expect` 声明、Ktor 客户端、领域模型 | 任何 `java.*` / `android.*` / 平台 API |
| `jvmMain` | Android 与 Desktop 共享的 JVM 实现（`ServerSocket`、`Zip`、ELF 解析、Ktor CIO 服务端） | 只有单一平台能用的 API |
| `androidMain` | `Context`、`SharedPreferences`、`Build.SUPPORTED_ABIS`、Media3、JNI | 桌面也会用到的实现 |
| `desktopMain` | `~/.kmp-pro` 持久化、rodio 播放器、JMTC、Skiko 调优 | 安卓也会用到的实现 |

**踩过的坑**：
- `expect` 与 `actual` 的可见性必须一致（`internal actual` 配 `public expect` 会编译失败）。
- Android 运行时没有 `com.sun.net.httpserver`，服务端一律用 Ktor CIO。
- KDoc 里写 `/api/v1/*` 会被当成嵌套块注释开头，导致 `Unclosed comment`；写成 `/api/v1/...`。

---

## 5. 已知结构性债务

| 问题 | 影响 | 处理 |
|------|------|------|
| 桌面入口在 `app/`，安卓入口在 `androidApp/` | 两个平台入口不对称，「安卓被剥离」的观感来源 | 计划合并进 `app`（见 `RESTRUCTURE_PLAN.md`） |
| `kmp-pro` 这个名字不表达「后端」角色 | 新人需读源码才知道分工 | 计划改名 `core` |
| `ui/component/`（21 文件）与 `ui/components/CommonComponents.kt`（1 文件）并存 | 命名易混淆 | 把 `CommonComponents.kt` 并入 `ui/component/` |
| `PlaybackEngine` / `PlaybackState` / `NoopPlaybackEngine` 全仓无使用 | 与 `PlatformPlayer` / `PlatformPlaybackState` 平行，容易误导 | 待清理 |
| `CachedMusicApiService.callApiCached` 全仓无调用方 | 缓存层目前是空转 | 待接入或删除 |
| `PlaybackControllerImpl.playCurrent(skipIfSame)` 参数未被使用 | 死参数 | 待清理 |
| `reference/netease-module-rust` 是**未注册的 submodule** | 索引里是 gitlink（mode 160000）但仓库根没有 `.gitmodules`，他人克隆后该目录为空 | 待修，见 `RESTRUCTURE_PLAN.md` §已知遗留 |
