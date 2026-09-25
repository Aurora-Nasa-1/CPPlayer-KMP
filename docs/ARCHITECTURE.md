# 架构与模块职责

本文档定义 CPPlayer 的模块边界与依赖规则。**新增代码前先读这一页**，
它说明了什么东西该放在哪个模块、以及谁可以依赖谁。

---

## 1. 三个模块的角色

```
        ┌──────────────────────────────────────────────┐
        │  app-android/         安卓入口点（236 行）     │
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
        │  core/                后端（11.4k 行）        │
        │  Provider · API · 缓存 · 播放内核 · 下载       │
        │  本地媒体扫描 · 本地流输出服务                 │
        └──────────────────────────────────────────────┘
```

### `core` —— 后端

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

### `app-android` —— 安卓入口点

只放「安卓进程启动所需、且不属于 UI」的东西：`MainActivity`、
`CPPlayerApplication`、Media3 `MediaSessionService`、`ControllerForwardingPlayer`、
Manifest 与 `res/xml/`。**不放业务逻辑。**

> 该模块独立存在是 AGP 9 的硬性要求（KMP 插件不再兼容
> `com.android.application`），不是历史遗留。详见 `RESTRUCTURE_PLAN.md` §1。

---

## 2. 唯一入口：`MusicBackend`

`core/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt`

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

**边界收敛已经开工**：`AppModel` 里留了一个明确标注为过渡用的逃生通道 ——

```kotlin
/** Transitional raw API access for operations not migrated yet. */
@Deprecated("Use musicRepository or a feature repository")
val api: cp.player.core.api.MusicApiService get() = backend.musicApi
```

编译器会为每一处调用报警告，所以「还剩多少没迁移」可以直接从构建日志读出来。

### 3.1 有正当理由的越界（保持现状）

| 位置 | 触碰 | 理由 |
|------|------|------|
| `AppModel.kt` | `playback.PlaybackController`、`provider.BackendProvider`、`provider.ProviderCookieStorage`、`util.SettingsStorage`、`control.LocalServerConfigStore`、`monitor.HealthMonitor`、`api.*` | **组合根**。后端的装配与生命周期只能在这里发生 |
| `platform/*MediaControls*` | `playback.PlaybackController` | 平台媒体控制（通知栏 / SMTC / 耳机按键）必须拿到控制器实例 |
| `Main.kt`、`DesktopRenderTuning.kt` | `util.defaultSettingsStorage` | 进程启动引导，早于 `MusicBackend.init` |
| `repository/AuthRepository.kt`、`repository/MusicRepository.kt` | `api.MusicApiService` | **数据访问层正是 API 的落点**，这两个仓库就是 `@Deprecated` 提示里说的 `musicRepository`。它们直接持用 `MusicApiService` 是设计意图，不是越界 |
| 全体 | `music.*` / `model.*` / `media.*` | 领域模型是前后端共享的数据契约，**本就该直接引用** |

### 3.2 应当收敛的越界（待办）

**A. UI 层仍在用已废弃的 `AppModel.api` 逃生通道**（7 个文件，12 处调用）

| 文件 | 调用点 | 涉及的 API |
|------|--------|-----------|
| `ui/component/AddToPlaylistSheet.kt` | 77、97 | `addTracksToPlaylist`、`createPlaylist` |
| `ui/component/CreatePlaylistDialog.kt` | 62 | `createPlaylist` |
| `ui/component/PlaylistPickerSheet.kt` | 125、130 | `getLoginStatus`、`getUserPlaylists` |
| `ui/component/QueueBottomSheet.kt` | 179 | `addTracksToPlaylist` |
| `ui/model/CommentScreenModel.kt` | 90、127 | `getComments`、`likeComment` |
| `ui/screen/PlayerScreen.kt` | 398 | `dislikeSong` |
| `ui/screen/PlaylistDetailScreen.kt` | 209、**352**、393 | `subscribePlaylist`、`getSongDetail`，以及把 `AppModel.api` **当参数传给** `MusicSourceFromApi.getPlaylistTracks` |

> ⚠️ 统计时别只搜 `AppModel.api.`（带点）—— 第 352 行是把 `AppModel.api`
> 作为参数传出去的，`AppModel.api.` 这种模式匹配不到它。
> 用 `grep -rn 'AppModel\.api\b'` 才不会漏。

迁移方向：把这些操作补进 `MusicRepository`（或按功能建 feature repository），
然后删掉 `AppModel.api`。**建议逐个提交，每迁一个就少一批编译警告**，
`AppModel.api` 本身可作为进度指标 —— 它删掉的那天就是边界收敛完成。

**B. UI 层直接 import 后端内部类型**

| 文件 | 问题 | 建议 |
|------|------|------|
| `ui/model/SearchScreenModel.kt` | import `api.MusicApiMethod`（API 方法常量泄漏到 UI 层） | 由 `repository/` 暴露语义化方法 |
| `ui/screen/SearchScreen.kt` | 同上 | 同上 |
| `ui/screen/HealthScreen.kt` | 直接 import `monitor.HealthMonitor` | 经 `AppModel` 暴露的只读状态 |
| `ui/component/SleepTimerDialog.kt` | 直接 import `playback.PlaybackController` | 经 `AppModel` 暴露 |
| `ui/screen/PlaybackSettingsScreen.kt` | 同上 | 同上 |

**判定标准**：如果一个 `ui/` 文件需要 import
`api.` / `provider.` / `monitor.` / `control.` 下的类型，那多半是缺了一个
由 `AppModel`（或 `MusicBackend`）暴露的语义化接口。

### 3.3 后端侧的依赖泄漏

`core` 的 `commonMain` 声明了 `composemediaplayer-audio` 依赖。
它虽然是「音频播放器」而非 UI 框架，但名字带 Compose，容易被误认为后端在依赖 UI。
**实际它提供的是 rodio 音频播放能力，与 Compose UI 无关**（其底层
`dev.nucleusframework:nucleus.rodio` 是纯 Rust JNI 封装）。

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
| `desktopMain` | `~/.cpplayer` 持久化（运行时配置目录，旧名 `.kmp-pro` 由 `DesktopDataDir` 一次性迁移）、rodio 播放器、JMTC、Skiko 调优 | 安卓也会用到的实现 |

**踩过的坑**：
- `expect` 与 `actual` 的可见性必须一致（`internal actual` 配 `public expect` 会编译失败）。
- Android 运行时没有 `com.sun.net.httpserver`，服务端一律用 Ktor CIO。
- KDoc 里写 `/api/v1/*` 会被当成嵌套块注释开头，导致 `Unclosed comment`；写成 `/api/v1/...`。

---

## 5. 已知结构性债务

| 问题 | 影响 | 处理 |
|------|------|------|
| **JNI 符号名与后端包名硬绑定** —— `JniProvider` 的全限定名决定 native 侧必须导出的符号（`Java_cp_player_core_provider_JniProvider_*`）。改包名会让已编译模块在首次调用时抛 `UnsatisfiedLinkError`，而 `System.load()` 仍然成功，症状伪装成「已加载但一调用就崩」 | 后端包名不可自由重构；第三方模块需随宿主同步重编 | `jni.rs` 已改并提交（模块仓库 `067c150`）；**尚差 push + bump gitlink**，见 `RESTRUCTURE_PLAN.md` §8 |
| 桌面入口在 `app/src/desktopMain/`，安卓入口在 `app-android/` | 两个平台入口不对称，「安卓被剥离」的观感来源。**注意：安卓侧受 AGP 9 约束必须独立，桌面侧不受约束** | 可选对称化，见 `RESTRUCTURE_PLAN.md` Phase 3 |
| ~~`ui/component/`（21 文件）与 `ui/components/CommonComponents.kt`（1 文件）并存~~ | 命名只差尾字母 `s`，猜错目录很容易 | ✅ **已合并**（2026-09-25），目录统一为单数 `ui/component/` |
| ~~`MusicBackend.playback: PlaybackEngine` 默认 `NoopPlaybackEngine`，全仓从未被赋过真实实现~~ | 门面上的公开属性，看起来像可插拔播放引擎，实际是空转的平行抽象；真正的播放路径走 `PlaybackController` / `PlatformPlayer` | ✅ **已删除**（2026-09-25，`f4a5141`） |
| ~~`PlaybackEngine` / `PlaybackState` / `EngineType` / `NoopPlaybackEngine`~~ | 与 `PlatformPlayer` 平行的另一套抽象，只被上面那条死链路引用，自身也无实现方 | ✅ **已删除**（2026-09-25，`f4a5141`）。注意 `PlaybackMetadata` **是活的**（`PlatformPlayer.load` 在用），已单独成文件保留 |
| ~~`PlaybackControllerImpl.playCurrent(skipIfSame)` 参数在函数体内从未被读取，8 个调用点全部传 `false`~~ | 死参数，暗示存在一个并不存在的「相同则跳过」能力 | ✅ **已删除参数**（2026-09-25，`f4a5141`） |
| `CachedMusicApiService.callApiCached` 全仓**唯一引用是它自己的声明**，且它一死，**整个 `core/cache/` 包（6 文件）跟着死** —— `ApiCache` / `CacheConfig` / `CacheEntry` / `CacheResult` / `Fingerprinter` 都只服务于它 | 缓存层的公开入口无任何调用方 ⇒ README 描述的「缓存层」目前**完全空转**。`CachedMusicApiService` 自身仍活着（`UnifiedMusicSourceImpl` 持有它），但只是纯转发壳。另：`AppModel.cachedApi` 与 `MusicApiServiceFactory.cachedInstance` 也都零引用 | **已确认范围，暂缓**（2026-09-25）。彻底清理需删 `core/cache/` 整包 + 收窄 `MusicBackend` / `MusicApiServiceFactory` / `AppModel` 公开面（约 6 删 5 改），属子系统级变更，不塞进卫生提交。**「接入还是删除」仍未决** |
| `reference/netease-module-rust` 是**未注册的 submodule** | 索引里是 gitlink（mode 160000）但仓库根没有 `.gitmodules`，他人克隆后该目录为空 | 待修，见 `RESTRUCTURE_PLAN.md` §7.1 |
| ~~`.qoder/` 有 132 个文件已被提交~~ | AI 生成的仓库 wiki + 一次性 diff 转储，会随代码漂移而失效 | ✅ **已移出版本控制**（2026-09-25），文件保留在磁盘上，见 `RESTRUCTURE_PLAN.md` §7.2 |
| `AboutScreen.kt` 用户可见文案仍是 `"KMP-PRO · Compose Multiplatform"` | 应用内显示旧项目名 | 待确认后改为 `CPPlayer` |
| `native/windows-smtc/` 只有一个 README | 占位目录，无代码 | 待实现或删除 |
| ~~`CommonComponents.kt` 的 `AppLogo` / `HeadlineSupportingRow` 无任何使用方~~ | 3 个公开 composable 里 2 个是死的（仅 `HeroBlock` 被 `SetupScreen` 使用） | ✅ **已删除**（2026-09-25，`f4a5141`），现仅剩 `HeroBlock` |
