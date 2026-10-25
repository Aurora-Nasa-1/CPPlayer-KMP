# 架构与模块职责

本文档定义 CPPlayer 的模块边界、依赖规则与源集分层设计。

---

## 1. 模块职责与依赖流向

```
┌──────────────────────────────────────────────┐
│  app-android/         安卓入口点              │
│  MainActivity · Application · MediaSession   │
└───────────────────┬──────────────────────────┘
                    │ depends on
┌───────────────────▼──────────────────────────┐
│  app/                 前端共享 UI 与桌面入口  │
│  Compose UI · Voyager · ScreenModel          │
│  主题与视觉 · 平台动作 (Android / Desktop)    │
└───────────────────┬──────────────────────────┘
                    │ depends on
┌───────────────────▼──────────────────────────┐
│  core/                后端领域核心            │
│  Provider · API · 缓存 · 播放内核 · 本地库    │
│  下载管理 · 本地流媒体服务                    │
└──────────────────────────────────────────────┘
```

依赖方向单向流动，严禁反向依赖：

### `core` —— 后端核心
负责全部音乐领域能力的跨平台实现。**规则：严禁包含任何 UI 代码（Compose 组件、Screen、ScreenModel 等）。**

- `api/`：音乐 API 契约常量、`MusicApiService` 容灾调度。
- `provider/`：音源插件系统（`BackendProvider`、`ModuleManifest`、`ProviderManager`、`ModuleManager`，支持 HTTP / Binary / JNI；Android 端不支持独立 binary，原生模块规范提升至运行 `.so`）。
- `lyrics/` / `lyricsplugin/`：歌词引擎与 Lyrico 歌词插件系统。
- `cache/`：`CachedMusicApiService`（读透缓存 + 写失效 + 多 Provider 容灾）、`Fingerprinter`、`ApiCache`。
- `playback/`：`PlaybackController`（播放队列、seek、切歌、歌词同步）、`PlatformPlayer` 抽象。
- `control/`：本地 HTTP 流媒体服务与局域网推送（`LocalServer`、`ExternalPusher`）。
- `integration/`：跨软件标准 API 契约导出（`/api/v1/...`）。
- `download/`：多任务媒体下载管理。
- `local/` / `media/`：本地音频文件扫描与媒体标签元数据解析。
- `music/`：跨音源统一领域模型（`TrackSummary`、`PlaylistSummary`、`CPMediaId`）。
- `MusicBackend.kt`：**后端统一对外的单例入口**。

### `app` —— 前端共享层
负责全平台 Compose Multiplatform 界面呈现与交互逻辑。

- `ui/screen/`：Voyager 页面组件。
- `ui/component/`：通用与业务 UI 组件库。
- `ui/model/`：页面级 `ScreenModel`。
- `ui/theme/`：主题系统（动态取色、深浅模式、纯黑省电模式）。
- `repository/`：`MusicRepository`、`AuthRepository` 等面向 UI 的业务领域仓库。
- `platform/`：桌面与 Android 平台能力抽象（窗口控制、系统媒体按键、渲染调优）。
- `update/` / `version/`：应用内更新检查与语义化版本比较。

### `app-android` —— Android 平台入口
遵循 Android Gradle Plugin (AGP) 规范的纯入口模块：`MainActivity`、`CPPlayerApplication`、Media3 `MediaSessionService` 与清单配置。**不包含具体业务实现。**

---

## 2. 前后端交互规则

1. **唯一统一入口**：前端访问后端能力的根入口是 `cp.player.core.MusicBackend`。
2. **UI 隔离**：UI 组件严禁直接依赖底层网络调用或音源 Provider，所有数据交互均通过 `repository/` 层（`MusicRepository`、`AuthRepository` 等）代理。
3. **共享领域模型**：`core` 中的领域模型（`cp.player.core.music.*`、`media.*`）作为标准数据契约，前后端直接共用。

---

## 3. 源集分层设计

工程采用清晰的多平台源集依赖层次：

```
commonMain  ──▶  jvmMain  ──▶  { androidMain, desktopMain }
```

| 源集 | 适用代码 | 禁止放置 |
|------|----------|----------|
| `commonMain` | 纯跨平台业务逻辑、`expect` 抽象、Ktor 客户端、领域模型 | 任何特定平台专有 API (`java.*`, `android.*`) |
| `jvmMain` | Android 与 Desktop 共用的 JVM 基础设施（Socket、Zip 归档、ELF 解析、Ktor CIO 服务端、Rhino JS 引擎） | 仅在 Android 或仅在 Desktop 存在的专有代码 |
| `androidMain` | Android 专有能力（`Context`、`SharedPreferences`、Media3、JNI 动态链接） | 桌面端同样需要的通用实现 |
| `desktopMain` | Desktop 专有能力（`~/.cpplayer` 运行时配置目录、桌面窗口吸附、WASAPI / Rodio 音频引擎、系统托盘） | Android 同样需要的实现 |

---

## 4. 核心设计原则

- **缓存读透策略**：读类接口自动走 LRU 缓存并支持多音源容灾回退；写类操作（如加歌单、收藏）精确失效对应缓存。
- **配置与数据隔离**：不同音源插件的缓存与账号 Session 严格按 Provider ID 和账号隔离，互不串扰。
- **能力网格化驱动**：音源声明自身支持的 `capabilities`，客户端根据能力矩阵自适应渲染或物理移除对应界面入口。
