# 对外集成方案（音源数据标准化 API）

> **目标**：让 CPPlayer 从「只能自己播」变成「可以被别的软件当作音源使用」——
> 由 CPPlayer 定义一套标准化接口，把搜索、曲目详情、播放地址、播放状态等
> **音源数据**暴露给第三方程序（游戏 radio、桌面挂件、OBS 插件、脚本、别的播放器）。
>
> **结论**：可行，且地基已经打好了一半 —— 本地 HTTP 服务端（Ktor CIO）、
> 字节直通流、Provider 抽象、统一 `CPMediaId` 路由都已存在。
> 本方案的重点**不是**「怎么起一个 HTTP 服务」，而是
> **「怎么保证暴露出去的东西是稳定的、跨音源通用的、且不会泄漏凭据」**。

---

## 1. 现状：先把两个方向分清

仓库里已经有一个「对外通信」功能，但它和本次需求**方向相反**，必须先切开，
否则会混进同一个抽象里造成语义混乱。

| | 已有：外部推送（出向） | 新增：对外集成（入向） |
|---|---|---|
| 角色 | CPPlayer 是**客户端** | CPPlayer 是**服务端** |
| 代码 | `control/ExternalPusher.kt` | 本方案新增 `integration/` |
| 接口由谁定义 | **接收端**（游戏 radio）定义 | **CPPlayer** 定义 |
| 数据流 | CPPlayer → `POST /api/v1/play-url` → 接收端 | 第三方 → `GET /api/v1/...` → CPPlayer |
| 用途 | 把流交给接收端播 | 把音源能力借给别人用 |

两者**互补不冲突**，共用同一份 `accessToken` 与绑定地址，但开关独立。
`ExternalPusher` 保持原样不动。

---

## 2. 核心决策：绝不暴露 `MusicApiService`

这是整个方案里最重要的一条，其它设计都是它的推论。

`MusicApiService`（60+ 方法）的返回值是 **`JsonElement`**，形状是**具体音源 Provider 的私有 JSON**：

```kotlin
// UnifiedMusicSourceImpl.kt:136 —— 解析网易云的 ar / al / dt 字段
val artists = this["ar"]?.jsonArray ?: this["artists"]?.jsonArray
val albumObj = (this["al"] as? JsonObject) ?: (this["album"] as? JsonObject)
durationMs = ((this["dt"] ?: this["duration"]) as? JsonPrimitive)?.longOrNull ?: 0L
```

如果把它当「标准 API」直接透传出去，会发生三件坏事：

1. **契约被绑死在某一个音源实现上**。Provider 支持 JNI / Binary / HTTP 三类，
   还有 `apiMap` 用来抹平端点差异 —— 透传 raw 等于绕过这层设计，换 Provider 契约立刻破。
2. **把适配责任推给集成方**。第三方要自己解析 `ar`/`al`/`dt`，这不该是他们的工作。
3. **接口随上游漂移**。上游 JSON 加字段/改名，第三方软件静默坏掉，而我们无法察觉。

### 结论：对外契约只允许建立在三层之上

| 层 | 类型 | 为什么可以 |
|---|---|---|
| 领域模型 | `music/` 的 `TrackSummary` / `PlaylistSummary` / `SearchResult` / `SongUrl` / `CPMediaId` | 已经是 provider-agnostic 的，与音源实现解耦 |
| 统一音源 | `UnifiedMusicSource` | 按 `CPMediaId` 自动路由到对应 Provider 或本地源 |
| 只读状态 + 媒体面 | `PlaybackController.state`、`LocalServer`（`/stream`） | 状态是领域模型；流是字节直通，已完成 |

**禁止**出现在 `core/integration/` 的 import 里：`api.MusicApiService`、`provider.*`、`cache.*`。

> 这条规则可以写成测试钉住 —— 仓库里已有先例（`CommentMethodMappingTest` 钉住 `getCommentMethod` 的分支）。
> 建议加一个 `IntegrationBoundaryTest`：扫 `integration/` 下的源码，出现 `import cp.player.core.api.`
> 或 `import cp.player.core.provider.` 即失败。契约靠约定守不住，要靠编译/测试守。

---

## 3. 必须先承认的能力缺口

`UnifiedMusicSource` 目前**只有 5 个方法**：

```kotlin
getTrackDetail(mediaId)              // ✅ 已领域模型化
getTrackDetails(mediaIds)            // ✅
getSongUrl(mediaId, level)           // ✅
search(keywords, type, providers)    // ✅
getUserPlaylists(providerId, uid)    // ✅
```

而歌单详情、歌词、专辑、歌手、评论、排行榜、日推…… **全都还在 `MusicApiService` 里返回 raw JSON**
（`MusicSourceFromApi` 只做了一部分解析）。

由此得到一条不可颠倒的顺序：

> **对外 API 面 = 已经完成领域模型化的能力集合。**
>
> 想要 `/api/v1/playlists/{id}`、`/api/v1/lyrics/{id}` 这类端点，
> **前置条件**是先把 `MusicSourceFromApi` 里的解析补齐成领域模型，再补 `UnifiedMusicSource` 方法，
> **最后**才加端点。反过来做（先定 API 再补模型）一定会顺手把 raw JSON 漏出去。

这也正是 `MusicBackend.kt:535` 那段 TODO（「增量迁移」）说的同一件事 ——
**本次方案与那条既有技术债是同一件事的两个面**，集成 API 的端点清单就是那份 TODO 的进度条。

### 3.1 暴露之前必须先修的契约缺陷（✅ 已于 2026-09-25 修复）

这四处都在本方案要依赖的层上，**不修就会被对外 API 放大** ——
本机 UI 调用时是「偶发怪现象」，远程调用时就是「返回错误结果且无从解释」。

下列问题均已修复并补上契约测试（新增 20 条用例，`core:desktopTest` 总计 104 条全绿）：

| # | 缺陷 | 位置 | 对外暴露后的后果 |
|---|---|---|---|
| 1 | `search(keywords, type, providers)` 的 **`providers` 参数被完全忽略**（KDoc 却承诺「跨多 Provider 聚合」「null 时搜索所有已加载的」）；`getUserPlaylists` 的 `providerId` 同样被忽略 | `UnifiedMusicSourceImpl.kt:125-131` | `/api/v1/search?provider=xxx` 会**静默返回别的音源的结果**。要么实现，要么把参数从接口上删掉 —— 不能让 KDoc 继续撒谎 |
| 2 | `CPMediaId.parse()` **抛 `IllegalArgumentException`**，三处调用都在 `try` 之外，破坏「可失败操作一律返回 `BackendResult`」的契约 | `UnifiedMusicSourceImpl.kt:21 / 56 / 103` | 远程发一个畸形 `mediaId` ⇒ **500 而不是 400**，且异常穿透 Ktor 路由 |
| 3 | `refreshLyrics` **缺少 `playCurrent` 那样的世代守卫**；切歌时旧任务的 `CancellationException` 被 `catch (e: Throwable)` 吞掉后写入 `LyricsState.Error` | `PlaybackControllerImpl.kt:595-627`（对比 `:656` 的 `loadGeneration` 守卫） | 切歌瞬间偶发「歌词获取失败」；`/api/v1/playback` 的歌词字段会抖动 |

修法都很小，**已全部落地**：

| # | 修法 | 落点 | 钉住它的测试 |
|---|---|---|---|
| 1 | `providers` / `providerId` 改为**校验条件**：传非活跃音源返回 `Unsupported`，空列表返回 `Error`；接口 KDoc 同步改成实话 | `UnifiedMusicSourceImpl` + `UnifiedMusicSource` KDoc | `UnifiedMusicSourceContractTest` |
| 2 | 新增 `CPMediaId.parseOrNull()`，三处调用改用它（`parse` 保留抛异常语义供已校验输入使用） | `CPMediaId` + `UnifiedMusicSourceImpl` | `UnifiedMusicSourceContractTest` |
| 3 | `refreshLyrics` 复用 `loadGeneration` 世代守卫（`emitIfCurrent`），并显式重抛 `CancellationException` | `PlaybackControllerImpl` | `LyricsGenerationGuardTest`（已验证：撤掉修复后 2/2 失败） |
| 4 | `toTrackSummary` 合并为单一实现（`TrackJsonMapper.kt`），`id` 用可选的 `mediaIdOverride` | 新文件 + 两处调用点 | `UnifiedMusicSourceContractTest` |

同源问题：`toTrackSummary` 的字段映射原先在 `MusicSourceFromApi` 与 `UnifiedMusicSourceImpl`
**各有一份拷贝且已经分叉**（前者有 `id`→`songId`、`name`→`song` 回退，后者没有）。
对外契约要保证「同一个 mediaId 在哪条路径上都解析成同一个结果」，**已合并成一份**。

另外顺手统一了成功码判定：原先 `MusicApiServiceImpl.callApi` 内联 `200/0/201/301`、
`MusicSourceFromApi.isSuccess` 再写一遍，现在都走 `ApiResponseCodes`（新增 `ApiResponseCodesTest` 钉住码表）。

> **仍未修**（属「坏味道」，不阻塞 Phase 0，见审查记录）：
> `ensureOrderScopeSafe()` 空占位函数、`parseSearchSongs` 的死参数 `type`、
> `getTrackDetails` 吞异常、`extractUrl` 的全树递归、`ExternalPusher` 的 404 回退不记忆、
> `MusicApiServiceFactory` 零调用方、`PlaybackControllerImpl` 的 god class 与 suspend/非 suspend 混用。
> 其中最后一条**已在 Phase 3 处理**：不直接暴露 `PlaybackController`，而是收窄成
> `IntegrationPlaybackControl`（只有 4 个动作），于是「suspend/非 suspend 混用」不再外溢到契约上。
> `PlaybackControllerImpl` 自身的 god class 问题仍在，见 §15。

---

## 4. 已经做好的地基（直接复用，不要重造）

| 已有能力 | 位置 | 复用方式 |
|---|---|---|
| Ktor CIO 服务端 | `control/LocalServer.jvm.kt` | **挂新路由到同一个引擎**，不新开端口 |
| 字节直通流 | `GET /stream?mediaId=&token=` | 对外只发指向它的地址；Range / Content-Range / Accept-Ranges 已透传，第三方能拖动进度 |
| 令牌机制 | `LocalServerConfig.accessToken` + `LocalServerConfigStore` | 复用，只扩展「怎么携带」（加 Bearer 头） |
| 统一路由 | `UnifiedMusicSourceImpl` | 集成服务的数据来源 |
| 桌面数据目录 | `util/DesktopDataDir.kt`（`~/.cpplayer/`） | 端点描述符落这里 |
| 读透缓存 | `CachedMusicApiService`（TTL 5 min） | 数据面**自动**享受，无需额外处理 |

### 4.1 一个已经成立、且必须保持的安全性质

`UnifiedMusicSourceImpl.kt:104-108`：

```kotlin
if (id.providerId == "local") {
    val item = localMusicSource.items().value.find { it.path == id.resourceId }
        ?: return BackendResult.Error("Local media not found: $mediaId")   // ← 白名单
    return BackendResult.Success(SongUrl(item.path, level, item.sizeBytes, null))
}
```

本地曲目是**按已扫描/已导入条目白名单查找**，不在列表里就报错。
所以 `GET /stream?mediaId=local://audio//任意路径` **不会**变成任意文件读取 —— 这是当前
设计的正确行为，且**在对外暴露流服务后变成了安全边界**。

> ⚠️ **必须保持**：任何人「优化」掉这次 `items()` 查找、改成直接读 `resourceId` 路径，
> 都会把 `/stream` 变成局域网任意文件下载漏洞。建议在这段加注释钉住。

---

## 5. 目标架构

**一个端口、一个引擎、两个面**。不新开端口 —— 集成方只需要一个 base URL，
多端口意味着多一套发现、授权、配置。

```
                     第三方软件（游戏 radio / 挂件 / 脚本 / OBS）
                                    │
                                    │  ① 读端点描述符（发现）
                                    ▼
                      ~/.cpplayer/integration.json
                                    │
                                    │  ② HTTP + Bearer token
                                    ▼
   ┌────────────────────────────────────────────────────────────┐
   │  Ktor CIO 引擎（复用 LocalServer 的，端口 = streamPort）      │
   │                                                            │
   │  ── 媒体面（exposeStream）──────────────────────────────    │
   │  GET  /health                    探活                       │
   │  GET  /stream?mediaId=&token=    字节直通（已有）            │
   │                                                            │
   │  ── 数据面（exposeDataApi，默认关）──────────────────────    │
   │  GET  /api/v1/meta               能力与版本协商              │
   │  GET  /api/v1/providers          已加载音源                 │
   │  POST /api/v1/search             搜索                       │
   │  GET  /api/v1/tracks/{mediaId}   曲目详情                   │
   │  POST /api/v1/tracks/batch       批量详情                   │
   │  GET  /api/v1/playback           播放状态                   │
   │  POST /api/v1/playback/{action}  播控（allowRemoteControl）  │
   └────────────────────────┬───────────────────────────────────┘
                            │
                            ▼
                  IntegrationService（用例层）
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
  UnifiedMusicSource   PlaybackController   LocalServer
   （CPMediaId 路由）     .state（只读）      （流地址生成）

   ✗  MusicApiService / provider.* / cache.*  ← 禁止依赖
```

### 5.1 分层职责

| 层 | 职责 | 允许依赖 |
|---|---|---|
| 路由（`jvmMain`） | HTTP 解析、鉴权、JSON 编解码、状态码 | `IntegrationService` |
| `IntegrationService`（`commonMain`） | 用例编排、领域模型 → DTO 映射、参数校验 | `UnifiedMusicSource`、只读播放状态 |
| DTO（`commonMain`） | 线上契约，`@Serializable` | 仅 `kotlinx.serialization` |

**关键设计选择：线上 DTO 独立于领域模型，不用同一个类。**

领域模型（`TrackSummary` 等）目前**没有** `@Serializable`（只有 `ModuleManifest` 有）。
两个选项：

- **A** 给领域模型加 `@Serializable` 直接当 DTO —— 省一层，但领域模型一改就破契约，
  而且它同时是前后端共享类型，演进会被两边绑死。
- **B** 独立 DTO + 映射函数 —— 多写一层，但契约稳定，可加 `apiVersion` / `deprecated` 字段。

**选 B。** 对外契约的稳定性优先级高于少写一层代码。
（注意 `ModuleManifest` 是反例：它**本身就是契约**，所以直接 `@Serializable` 是对的。
区别在于「这个类型是不是只服务于线上」。）

---

## 6. 模块落点

新增包 `core/src/commonMain/kotlin/cp/player/core/integration/`：

| 文件 | 源集 | 职责 |
|---|---|---|
| `IntegrationDto.kt` | commonMain | 线上 DTO + `apiVersion` 常量 |
| `IntegrationService.kt` | commonMain | 用例层（唯一有业务逻辑的地方） |
| `IntegrationRouting.kt` | commonMain | 路由表常量、鉴权与开关的判定规则（纯函数，可测） |
| `IntegrationServer.jvm.kt` | **jvmMain** | Ktor 路由实现（`actual`） |

> `ktor-server-*` 依赖只在 `jvmMain` 声明（`core/build.gradle.kts`），
> 所以 Ktor 相关实现**必须**放 `jvmMain`，`commonMain` 只放纯逻辑。
> `kotlin-serialization` 插件与 `kotlinx-serialization-json` 在 `commonMain` 可用，DTO 放 commonMain 没问题。

### 6.1 与 `LocalServer` 的关系

复用同一个 Ktor 引擎，但**不把数据面塞进 `KtorLocalServer` 类里** ——
那个类的职责是「字节流转发」，塞进 JSON 路由会让它同时承担两件事。

建议：

```kotlin
// LocalServer.kt —— 保持职责单一，只多一个路由挂载点
// （已按此实现，见 §13 Phase 0；接口名定为 IntegrationRouteMount，
//   避免与路由表常量对象 IntegrationRoutes 重名）
expect fun createLocalServer(
    config: LocalServerConfig,                          // 绑定期字段（地址/端口/令牌）
    integration: IntegrationRouteMount? = null,         // 数据面路由（可选）
    activeConfig: () -> LocalServerConfig = { config }, // 路由级开关的实时读取器
    resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
): LocalServer
```

> `activeConfig` 是后加的一环：光有 `integration` 还不够 ——
> 媒体面的 `exposeStream` 同样要按请求现读，所以实时读取器必须是引擎级参数，
> 而不是只挂在 `integration` 上。
>
> `resolveStreamUrl` **必须留在最后**：它是主 lambda，
> 往它后面插参数会让 `createLocalServer(config) { … }` 这类既有调用点
> **静默改绑**（尾随 lambda 会绑到新参数上）。实现时踩过一次。

### 6.2 一个容易踩的细节：开关必须**运行时读取**

`applyOutputConfig` 的重建判定只看绑定期字段（`enabled` / `bindAddress` / `streamPort` / `accessToken`）——
`receiverBaseUrl` 这类纯推送侧改动不会重启端口。

新的 `exposeDataApi` / `allowRemoteControl` 属于**路由级开关**，同样不该重启端口。
但 `createLocalServer(config, ...)` 是**构造期捕获**配置的，直接读会拿到旧值。

**所以开关必须以「实时提供者」形式传入**，沿用 `ExternalPusher` 已有的写法：

```kotlin
private val pusher: ExternalPusher by lazy { createExternalPusher { activeOutputConfig } }
//                                                        ↑ 每次请求时现读，配置变更无需重建
```

数据面路由同理：`{ activeOutputConfig }`。

---

## 7. 配置模型（向后兼容）

在 `LocalServerConfig` 上**新增键**，不改已有键的语义：

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | **保持原义**：总开关，绑定端口 |
| `exposeStream` | `true` | 媒体面（`/stream`） |
| `exposeDataApi` | **`false`** | 数据面（`/api/v1/...`）—— 新的攻击面，默认关 |
| `allowRemoteControl` | **`false`** | 播控写操作 |
| `bindAddress` / `streamPort` / `accessToken` | 不变 | 继续复用 |
| `pushEnabled` / `receiverBaseUrl` | 不变 | 出向推送，不受影响 |

新增持久化键沿用现有命名风格（`local_server_*`）：

```
local_server_expose_stream
local_server_expose_data_api
local_server_allow_remote_control
```

> `LocalServerConfigStore.read` 对缺失字段回退默认值，所以老配置文件天然兼容，
> **不需要**写迁移逻辑。

---

## 8. v1 契约

### 8.1 通用约定

- 前缀 `/api/v1`，**只做加法**：v1 内不删字段、不改字段含义。
- 所有响应是 JSON 对象；错误统一形态：
  ```json
  { "error": { "code": "provider_unavailable", "message": "…" } }
  ```
  HTTP 状态码语义化：`400` 参数错、`401` 未授权、`403` 面未开放、`404` 找不到、`502` 上游失败。
- **响应体里永不出现 cookie / 上游签名 URL**（见 §10）。
- 版本协商靠 `GET /api/v1/meta`，集成方**应当**先调它再决定用哪些端点。

### 8.2 `GET /api/v1/meta`

```json
{
  "app": "CPPlayer",
  "apiVersion": 1,
  "capabilities": ["search", "track", "trackBatch", "playback", "stream"],
  "provider": { "id": "netease", "name": "NeteaseCloudMusicApi", "version": "1.2.0" },
  "loggedIn": true
}
```

`capabilities` 是**关键字段**：它如实反映 §3 的能力缺口。
集成方据此判断端点可用性，而不是靠试错。将来补上歌单/歌词就追加字符串，v1 不破。

### 8.3 `GET /api/v1/providers`

已加载音源列表（`id` / `name` / `version` / `type` / `active`）。
**不含** cookie、模块路径、`apiMap` 等内部信息。

### 8.4 `POST /api/v1/search`

```json
// 请求
{ "keywords": "周杰伦", "type": 1, "limit": 30 }
```
```json
// 响应
{
  "songs": [{
    "mediaId": "netease://song/12345",
    "name": "…", "artist": "…", "album": "…",
    "coverUrl": "https://…", "durationMs": 269000,
    "streamUrl": "http://127.0.0.1:8080/stream?mediaId=netease%3A%2F%2Fsong%2F12345&token=…"
  }],
  "playlists": [], "artists": []
}
```

`streamUrl` 直接给**本机** `/stream` 地址（含 token），第三方拿去就能播 —— 不需要它理解 `mediaId`。
同时也给 `mediaId`，供它后续调 `/tracks/{mediaId}`。

### 8.5 `GET /api/v1/tracks/{mediaId}` · `POST /api/v1/tracks/batch`

`mediaId` 需 URL 编码（含 `://` 与 `/`）。响应为 `TrackSummary` 的 DTO 形态。
`batch` 请求体 `{ "mediaIds": [...] }`，返回数组；**单项失败不整体失败**，
失败项以 `{ "mediaId": "…", "error": "…" }` 形式出现在数组里。

### 8.6 `GET /api/v1/playback`

```json
{
  "isPlaying": true,
  "positionMs": 42000,
  "currentIndex": 3,
  "qualityLevel": "exhigh",
  "currentTrack": { "mediaId": "…", "name": "…", "artist": "…", "durationMs": 269000, "streamUrl": "…" },
  "queueLength": 24
}
```

### 8.7 `POST /api/v1/playback/{action}`（需 `allowRemoteControl`）

`play` / `pause` / `next` / `previous`。
**不提供** `play-url`（远程指定任意 URL 播放 = 把本机变成任意音频播放器，无必要）。
要播什么，第三方先 `search` 拿到 `mediaId`，再由用户在本机决定 —— v1 不开放远程入队。

> **计划里原本列了 `stop`，实现时去掉了（2026-09-25）。** 播放内核没有非破坏性的
> 「停止」：只有 `pause()` 与 `clearQueue()`。把 `stop` 映射到后者意味着一次远程调用
> **静默销毁用户攒的队列**，且不可撤销。所以 `stop` 返回 `400` 并指路 `pause`。
> 教训：**别让「动作名看起来该有」决定契约** —— 要落到内核真有什么操作上。
>
> 返回体是**动作之后**的状态快照（不是动作前），因为 HTTP 响应会等动作执行完才返回。
> 这解决了 §3.1 末尾记的那个「Phase 3 需要一并处理」的问题。

### 8.8 鉴权

- 绑定**回环**时：允许无 token（便于本机脚本）。
- 绑定**非回环**时：数据面**强制**要求 token，且不可关闭。
- 两种携带方式：
  - `Authorization: Bearer <accessToken>`（**推荐**，日志友好）
  - `?token=<accessToken>`（兼容已有接收端与浏览器直接拉流）

> 现在 `ensureAuthorized()` 只认 `?token=`。加 Bearer 是**加法**，
> 已有接收端不受影响。

---

## 9. 发现机制：端点描述符

第三方怎么知道端口和 token？不要让它手抄。

`~/.cpplayer/integration.json`（**必须走 `DesktopDataDir`**，仓库约定，禁止硬编码 `user.home`）：

```json
{
  "app": "CPPlayer",
  "apiVersion": 1,
  "baseUrl": "http://127.0.0.1:8080",
  "token": "a1b2c3…",
  "pid": 12345,
  "updatedAt": "2026-09-25T18:30:00+08:00"
}
```

写入时机：服务启动成功 / 端口或令牌变更 / 进程退出时删除（`pid` 供集成方判断是否陈旧）。

**为什么不用 mDNS**：多一个依赖、多一层网络权限、防火墙弹窗，
而本机集成场景读一个文件就够了。局域网场景集成方本来就要用户告知地址，描述符也能拷过去。

> Android 没有 `~/.cpplayer`，发现方式退化为「用户在设置页读端口」。
> **平台差异只体现在发现方式，不体现在契约**（见 §10）。

---

## 10. 平台策略

| 平台 | 角色 | 发现方式 |
|---|---|---|
| Desktop | **v1 主目标**：作为服务宿主 | `~/.cpplayer/integration.json` |
| Android | 同一份 `core` 代码可跑（CIO 已在 `jvmMain` 共用），局域网可用 | 设置页展示地址/令牌 |

Android 上「另一个 App 来消费」更自然的是 ContentProvider / AIDL，
但 **v1 不做**，理由：ContentProvider 是 Android 独有的，桌面软件消费不了；
而 CIO 服务端一套契约两端通用。

将来若要做，**应在 `IntegrationService` 之上加一个 ContentProvider 适配层**，
而不是另写一套逻辑 —— 保持「一套用例，多种传输」。

---

## 11. 并发与线程安全（真实隐患）

`MusicBackend.backendScope` 是 **`Dispatchers.Main`**（Android 主线程 / 桌面 EDT），
队列、播放状态等都在它上面串行读写 —— 这是**有意设计**，改成 `Dispatchers.Default`
会立刻变成真数据竞争。

Ktor 的请求处理跑在 **CIO 的线程**上，于是：

| 操作 | 安全性 | 处理 |
|---|---|---|
| 读 `playbackController.state.value` | ✅ 安全 | `StateFlow` 读是线程安全的 |
| 调 `unifiedSource.*` | ✅ 安全 | 都是 `suspend`，内部自己切线程 |
| **写**：`play` / `pause` / `next` / 切 Provider | ❌ **不安全** | **必须** `withContext(controlDispatcher) { … }` 回控制线程 |

> 这条已落成 `IntegrationService` 的硬约定，并由 `IntegrationPlaybackControlTest` 钉住
> （含一条**线程名断言**：动作必须跑在控制线程上）。
> 在 Ktor 线程直接调 `playbackController.play()` 会与 UI 抢状态，
> 症状是偶发跳歌 / 队列错乱，且**难以复现**。
>
> **用 `withContext` 而不是 `launch`** —— 原计划这里写的是 `backendScope.launch`，
> 实现时改了：`launch` 会让 HTTP 响应**先于动作返回**，集成方拿到的状态快照就是动作之前的。
> 也不要用 `withContext(backendScope.coroutineContext)`：那会把请求协程的 Job 换掉，
> 破坏结构化并发。正确写法是只取 dispatcher，见 §13 Phase 3 的产出说明。

---

## 12. 安全清单

1. `exposeDataApi` 与 `allowRemoteControl` **默认 false**。
2. 绑定非回环 ⇒ 数据面**强制**要求 token（不可关闭）。
3. 支持 `Authorization: Bearer`；`?token=` 仅作兼容保留。
4. **响应体永不包含 cookie / 上游签名 URL** —— 只给本机 `/stream?mediaId=…`。
   这是现有 `StreamTarget` 设计的边界（`cookie` 由服务端注入、不下发），必须保持。
5. 本地曲目**白名单查找**性质必须保持（§4.1）。
6. CORS：浏览器里的第三方页面要跨域调用 ⇒ 需要**显式白名单**（默认关）。
   选项：加 `ktor-server-cors` 依赖（需新增 `libs.versions.toml` 条目），
   或手写几个响应头避免新依赖。**建议后者**，因为只需要 3 个头。
7. 日志：**不打 token、不打 cookie**。
8. 防滥用：数据面开放后可能被当成免费音源刷。读透缓存（TTL 5 min）会天然吸收重复请求，
   但建议 Phase 4 加按 IP 的简单令牌桶。

---

## 13. 实施阶段

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **Phase 0** 骨架 + 契约钉住 ✅ **已完成（2026-09-25）** | `integration/` 包、DTO、`IntegrationService`（仅 `meta` + `providers`）、路由挂到现有引擎、配置扩展（默认关）、`IntegrationBoundaryTest` | desktop 起服务，`curl /api/v1/meta` 返回 `apiVersion` |
| **Phase 1** 只读数据面 ✅ **已完成（2026-09-25）** | `search` / `tracks/{id}` / `tracks/batch` / `playback` | 用例层用 fake `UnifiedMusicSource` 做契约测试；手工 curl 对真实音源 |
| **Phase 2** 发现 + 鉴权收紧 ✅ **已完成（2026-09-25）** | `integration.json` 描述符、Bearer 头、非回环强制 token、设置页开关（`LocalServerSettingsScreen`） | 描述符端口与实跑一致；非回环无 token 得 401 |
| **Phase 3** 写操作 + 事件 ✅ **已完成（2026-09-25）** | `allowRemoteControl` 下的播控、`GET /api/v1/events`（SSE 状态推送） | 用可观测 fake 断言写操作确实回 Main；`curl -N` 看事件流 |
| **Phase 4** 能力扩展 | 把 `MusicSourceFromApi` 的歌单详情/歌词解析补成领域模型 → 补 `UnifiedMusicSource` 方法 → 才加端点 | 每加一个端点，`meta.capabilities` 同步追加 |

> **Phase 0 实际产出（2026-09-25）**
>
> - `core/src/commonMain/.../integration/`：
>   `IntegrationDto.kt`（错误信封 / `MetaDto` / `ProvidersDto` / `apiVersion` / capabilities）、
>   `IntegrationService.kt`（用例层；输入用中性类型 `IntegrationProviderInfo`，不碰 `BackendProvider`）、
>   `IntegrationRouting.kt`（路由表常量、挂载点 `IntegrationRouteMount`、`decideDataApiGate` 纯函数、`parseBearerToken`）
> - `core/src/jvmMain/.../integration/IntegrationServer.jvm.kt`：Ktor 路由 + 统一错误响应 + `Json { encodeDefaults = true }`
> - `LocalServerConfig` 新增 `exposeStream`(默认 true) / `exposeDataApi`(默认 false) / `allowRemoteControl`(默认 false)
>   与三个 `local_server_*` 持久化键；老配置天然兼容（缺键回退默认，**且解析失败一律回退到「关」**）
> - `createLocalServer` 新增 `integration` 与 `activeConfig`（实时读取器）
> - 新增测试 27 个：`IntegrationRoutingTest`(12) / `IntegrationDataApiTest`(9) / `IntegrationBoundaryTest`(1) /
>   `LocalServerConfigIntegrationKeysTest`(5)；`:core:desktopTest` 共 131 个全绿
> - 面向集成方的契约手册：`docs/dev/INTEGRATION_API.md`
>
> **实现期踩到的三个坑（都不是业务问题，但都会静默出错）**
> 1. 路由表常量对象与挂载句柄接口**不能同名**（都叫 `IntegrationRoutes` → `Redeclaration`）
>    ⇒ 挂载句柄改名 `IntegrationRouteMount`。
> 2. 往 `createLocalServer` 的**主 lambda 之后**插参数，会让 `createLocalServer(config) { … }`
>    这类既有调用点**静默改绑**（尾随 lambda 绑到新参数上，报的错还指向别处）。
>    `resolveStreamUrl` 必须留在参数表最后。
> 3. 批量改名用 `replace_all` 时，`IntegrationRoutes` 是 `createIntegrationRoutes` /
>    `KtorIntegrationRoutesHandle` 的**子串**，会被连带改坏。改名后必须 grep 复核。

> **Phase 1 实际产出（2026-09-25）**
>
> - 四个只读端点全部可用：`POST /api/v1/search`、`GET /api/v1/tracks/{mediaId}`、
>   `POST /api/v1/tracks/batch`、`GET /api/v1/playback`。
> - `IntegrationCapabilities` 追加 `search` / `track` / `trackBatch` / `playback`
>   （`stream` 仍随 `exposeStream` 条件出现）。**capabilities 如实反映已实现的能力**，
>   `INTEGRATION_API.md` §1 的状态表与之同步。
> - 错误分类集中到 `FailureKind` 枚举（`httpStatus` 与 `errorCode` 同一张表），
>   于是「什么错配什么码」在 `commonMain` 单测里就能钉住，路由层只剩
>   `HttpStatusCode.fromValue(...)` 一步 —— `commonMain` 里没有 Ktor 类型，
>   这是唯一能把这张表测干净的结构。
> - 新增错误码 `unsupported`（**501**），与 `upstream_failed`（502）刻意区分：
>   前者是「换音源」，后者才是「重试」。混为一谈会让集成方对着不支持该功能的音源无限重试。
> - 新增测试净增 **40 个**（131 → **171 全绿**）：
>   `IntegrationServiceTest`(28，新增) / `IntegrationDataApiTest`(9→20) /
>   `IntegrationRoutingTest`(12→13) / `IntegrationBoundaryTest`(1，不变)。
> - 边界测试扫描 `integration/` 源码，禁止出现 `cp.player.core.api.` / `.provider.` / `.cache.`，
>   并带反空转断言（`files.size >= 3`）—— 否则「零命中」与「扫描没跑起来」分不清。
>
> **Phase 1 的两个契约取舍（都是「宁可说不知道，也不假装知道」）**
> 1. **曲目不存在表现为 `502` 而不是 `404`** —— 领域层 `getTrackDetail` 把「不存在」
>    与「取数失败」收敛成同一种 `Error`，用例层无从区分。`FailureKind.NOT_FOUND`
>    保留在词汇表里但**目前没有代码路径产出**，枚举上已注明。有测试钉住当前的 502 行为：
>    Phase 4 改掉它时测试会失败，从而强制同步文档与 capabilities。
> 2. **`limit` 是本地截断，不是上游分页** —— 上游搜索接口没有分页参数，服务端仍是
>    「取全量 → `take(limit)`」。文档里明确写了它**不能**减少上游请求量，别当 `page_size` 用。
>
> **判别力验证（mutation test）**：把 `trackWith` 里的
> `validateMediaId(mediaId)?.let { return it }` 去掉后，**恰好 3 个用例失败** ——
> `IntegrationServiceTest > 空 mediaId 返回 400`、
> `IntegrationServiceTest > 畸形 mediaId 返回 400 且不触碰上游`、
> `IntegrationDataApiTest > track 的畸形 mediaId 返回 400`，其余全绿。
> 证明「畸形 id 本机判死、不触碰上游」这条性质真的被钉住了，而不是碰巧通过。验证后已还原。

> **Phase 2 实际产出（2026-09-25）**
>
> - **端点描述符**（发现机制，§9）：`IntegrationDescriptor` + `IntegrationDescriptorWriter`
>   契约在 `commonMain`，桌面实现写 `~/.cpplayer/integration.json`（走 `DesktopDataDir`），
>   Android 是**有理由的**空实现（见下）。字段：`app` / `apiVersion` / `baseUrl` /
>   `token` / `pid` / `updatedAt`。
> - **非回环强制 token 收紧到媒体面**：`/stream` 原本写的是
>   `if (!config.requiresToken) return true` —— 只要没配令牌就放行。于是
>   「绑定 `0.0.0.0` + 令牌为空」是一个**可达状态**（用户手工清掉令牌键即可），
>   同网段任何人都能无限拉流；而数据面一直正确拒绝。
>   修法不是各改一处，而是把规则抽成 `isTokenSatisfied(config, provided)`，
>   **媒体面与数据面共用同一个函数** —— 同一个规则写两遍正是这次漂移的成因。
> - **设置页开关**：`LocalServerSettingsScreen` 新增「跨软件集成（数据面）」卡片
>   （开放数据面 / 开放媒体面 / 允许远程播控 + 数据面地址 + 描述符路径提示）。
>   数据面默认关闭，**没有 UI 就等于不可达**，所以这一步是让 Phase 1 真正可用的前提。
> - 新增测试 17 个：`IntegrationDescriptorTest`(10) / `MediaFaceAuthorizationTest`(5) /
>   `LocalServerConfigIntegrationKeysTest`(+1)，并扩展 `IntegrationBoundaryTest` 的源集覆盖。
>
> **描述符实现里三个「集成方会真的踩到」的细节**
> 1. **原子替换**：先写同目录临时文件再 `ATOMIC_MOVE`。集成方可能在**任意时刻**读它，
>    直接覆写会读到半截 JSON。临时文件必须与目标**同目录** —— 跨目录的 move 不是原子的，
>    就丢掉了这个保证。
> 2. **权限收紧到仅属主可读写**（POSIX 平台；Windows 无 POSIX 视图则跳过）。
>    诚实说明：令牌本来就明文存在 `cp_player_prefs.properties` 里，所以对**同用户**进程
>    这不是新增暴露面，**跨用户**才是 —— 权限收紧挡的正是后者。
> 3. **`clear()` 必须幂等且不抛**：它在服务停止路径上被调用，抛异常会让清理逻辑自己制造故障。
>
> **为什么 Android 是空实现（不是「以后再说」）**
> 描述符的价值在于「第三方进程能读到它」。Android 上两个选项都不成立：应用私有目录
> 别的 App 读不到（写了没意义）；外部存储需要权限且会把明文令牌写到全机可读的位置
> （净负收益）。所以 Android 的发现退化为「用户在设置页读地址与令牌」。
> **这是发现方式的平台差异，契约不变** —— 将来要给 Android 上的第三方 App 供数，
> 正确形态是 **ContentProvider/AIDL 适配层加在 `IntegrationService` 之上**，
> 而不是文件发现（保持「一套用例，多种传输」）。
>
> **描述符只在服务确认在跑之后才写**：`LocalServer.start()` 把绑定失败吞进 `status.error`
> 而**不抛异常**（端口被占用最常见），所以「调用过 `start()`」≠「服务可用」。
> 无条件发布会让集成方读到一个连不上的地址 —— 比没有描述符更难排查：
> 文件不存在语义明确，连接被拒则要猜是服务没起、防火墙还是地址写错。
>
> **Phase 2 的一个诚实缺口**：「非回环 + 无令牌 → 401」这条分支**没有** HTTP 端到端覆盖，
> 因为跑它需要真的绑定 `0.0.0.0`，而本仓库所有测试都刻意只绑回环（避免 Windows 防火墙
> 弹窗与 CI 抖动）。替代手段是两层：`isTokenSatisfied` 的**纯函数测试**覆盖每个分支，
> 外加一条**源码断言**钉住 `KtorLocalServer` 委托给它、且不再出现
> `!config.requiresToken` 短路与内联令牌比较。会回归的正是那一行，所以这条断言有效。

> **Phase 3 实际产出（2026-09-25）**
>
> - **写路径**：`POST /api/v1/playback/{action}`，四个动作（`play` / `pause` / `next` / `previous`），
>   返回**动作之后**的状态快照。
> - **收窄的写接口**：`IntegrationPlaybackControl`（`commonMain`，只有 4 个方法），
>   不直接暴露 `PlaybackController` —— 否则「对外能做什么」会被上游接口的形状决定，
>   还会把 suspend / 非 suspend 混用外溢到契约上（§3.1 记的那条）。
> - **`stop` 刻意不做**：内核只有非破坏性的 `pause()` 与破坏性的 `clearQueue()`。
>   返回 `400` 并指路 `pause`（见 §8.7）。
> - **事件流**：`GET /api/v1/events`（SSE），首帧快照 + 变化推送 + 15 秒注释行心跳。
> - **能力清单**：新增 `events`（恒在）与 `playbackControl`（随 `allowRemoteControl`）。
> - **设置页**：三个开关（媒体面 / 数据面 / 远程播控）。
> - 新增测试 16 个：`IntegrationPlaybackControlTest`(10) + `IntegrationDataApiTest` 的
>   HTTP 端到端（播控 happy path / 403 / `stop` 400 / 未知动作 400 / 事件流三条）。
>   **全套 204 个测试全绿**（Phase 2 时为 188）。
>
> **写路径必须回到控制线程 —— 这是 Phase 3 唯一的真风险**
>
> `MusicBackend.backendScope` 跑在 `Dispatchers.Main`（Android 主线程 / 桌面 EDT），
> 队列与播放状态的读写天然串行。Ktor 的请求线程直接改状态就是**真数据竞争**，
> 症状是偶发跳歌 / 队列错乱，**本地几乎复现不出来**。所以：
> - 用 `withContext(controlDispatcher) { perform(control) }`；
> - **不是** `launch`：HTTP 响应必须等动作**完成**，否则返回的快照是动作之前的；
> - **不是** `withContext(backendScope.coroutineContext)`：那会把请求协程的 Job 换掉，破坏结构化并发；
> - dispatcher 从 `backendScope.coroutineContext[ContinuationInterceptor]` 派生，而不是硬编码
>   `Dispatchers.Main` —— 否则这条规则不可测（`core` 的 desktopTest 没有 `kotlinx-coroutines-swing`，
>   `Dispatchers.Main` 根本不可用）。
>
> **变异测试证明这条规则真的被钉住**：把 `withContext(controlDispatcher)` 换成直接调用，
> 恰好 **2 个**测试失败（`写操作在控制线程上执行而不是调用方线程`、
> `手动调度器下动作确实进了控制线程队列`），其余 8 个照旧通过。
> ⚠️ `响应要等动作完成而不是即发即忘` **没有**失败 —— 它钉的是「`launch` vs 直接调用」，
> 与 dispatcher 无关。两条性质是分开钉的，别把它们的覆盖范围混为一谈。
>
> **Phase 3 抓到的一个真 bug：`drop(1)` 会丢事件（已修）**
>
> 事件流最初的写法是「先手动写一帧快照，再 `merge(states.drop(1).map{…}, heartbeats)`」。
> 这有一个**窗口**：快照是从 `states.value` 读的，而 `merge` 是**异步**订阅的；
> 窗口内发生的状态变化会被 `drop(1)` 连着重放一起丢掉。症状是**随机丢事件** ——
> 集成方一直停在旧状态，直到下一次变化才「自己好起来」，本地几乎复现不出来。
> 由 `事件流先发快照再推变化` 抓到：该测试在收到快照后**立刻**改状态，正好落进窗口。
> 修法是让快照与后续变化走**同一条流**（`states.map{…}`，靠 `StateFlow` 订阅时的重放拿首帧），
> 既不重复也不留窗口。
> **⚠️ 不要把这条测试改成「先 sleep 一下再改状态」** —— 那会绕开窗口，测试照样绿，线上照旧丢事件。
>
> **Phase 3 的两个诚实缺口**
> 1. 事件流**没有**覆盖「客户端半途断开 → 服务端安静回收」这条路径（需要模拟半开连接）。
>    实现里 `catch (Throwable)` 保证不污染日志，但没有测试钉住「断开后订阅确实被回收」；
>    当前依赖 `respondTextWriter` 的块结束即回收。
> 2. 心跳**只在被读到的时候**才算验证过。测试里心跳间隔是 15 秒，而断言超时是 5 秒，
>    所以测试**不会**等到心跳 —— 心跳路径只在诊断过程中被观察到过（`curl -N` 同）。
>    要真正钉住它需要把间隔参数化，属 Phase 4 的收尾项。
>
> **一个测试脚手架的教训：SSE 客户端不能用 `HttpURLConnection`**
> 事件流**永远不结束**，而 `HttpURLConnection` 要先把响应头读出来才知道怎么给 body；
> 它拿到 `Transfer-Encoding: chunked` 后按需解 chunk —— 一旦缓冲策略变化，测试就表现成
> 「连上了但一行都收不到」，**看不出是服务端没发还是客户端没读**。
> 换成裸 socket（请求发 **HTTP/1.0**，正文按原始字节计数）后，「服务端到底发没发」
> 变成一个可以直接断言的量（`bytesRead`），这才定位到上面那个丢事件的 bug。
> 调试期一度以为服务端没 flush，实际是**服务端发了、客户端没解出来**。

**顺序不可颠倒**：Phase 4 的三步是「模型 → 接口 → 端点」，反过来必然漏出 raw JSON。

> ⚠️ **KDoc 陷阱**（本仓库已踩过）：KDoc 里写 `/api/v1/*` 会被当成嵌套块注释开头，
> 导致 `Unclosed comment` 编译失败。**代码注释里一律写 `/api/v1/...`**。

---

## 14. 明确不做（避免方案膨胀）

| 不做 | 理由 |
|---|---|
| 暴露 raw `MusicApiService` / `JsonElement` | §2，契约会被绑死在单个音源上 |
| 转码 / 容器重封装 | 现有字节直通已能拖动进度；转码引入 CPU 与延迟，无必要 |
| 账号类接口（登录 / 登出 / 扫码 / 发评论） | 远程让人替你登录会放大 token 泄漏面。**只读已登录态的数据** |
| 远程 `play-url`（指定任意 URL 播放） | 等于把本机变成任意音频播放器，无正当用途 |
| Android ContentProvider | v1 不做；将来作为适配层加在 `IntegrationService` 之上 |
| mDNS / 服务发现广播 | 描述符文件足够且零依赖 |

---

## 15. 风险与缓解

| 风险 | 说明 | 缓解 |
|---|---|---|
| **契约被 raw JSON 污染** | 最可能的失败模式：为了快，直接透传 provider JSON | `IntegrationBoundaryTest` 禁止 `integration/` import `api.` / `provider.` |
| 领域模型改动静默破契约 | `TrackSummary` 加字段或改名 | 独立 DTO（§5.1）+ DTO 序列化契约测试 |
| 远程被当成免费音源 | 数据面开放后可能被刷 | 默认关 + token + 缓存吸收 + 限流（Phase 4） |
| 写操作线程错位 | 与 UI 抢状态，偶发跳歌 | 硬约定：写操作必须回 `backendScope`（§11） |
| 端口被占用 | 与现有 `streamPort` 冲突 | 沿用现有 `LocalServerStatus.error` 呈现，不新增机制 |
| 本地文件越权读取 | 若白名单查找被「优化」掉 | §4.1 加注释钉住 |
| KDoc `/api/v1/*` | 编译失败（已踩过） | 一律写 `/api/v1/...` |

---

## 16. 与现有文档的关系

| 文档 | 关系 |
|---|---|
| `ARCHITECTURE.md` | ✅ **已同步**（2026-09-25）：§1 包表加了 `integration/` 行，§5 债务表加了「能力缺口」条目 |
| `PROVIDER_DEV_GUIDE.md` | **面向 Provider 作者**（怎么给 CPPlayer 供数据）；本方案是**反向**（怎么把数据给第三方），两者互补 |
| `RESTRUCTURE_PLAN.md` | 本方案沿用其 Phase 编排与「明确不做」的写法 |
| `MusicBackend.kt` 增量迁移 TODO | **同一件事的另一面**：那份「增量迁移」的进度就是 `meta.capabilities` 的内容 |
| `docs/dev/INTEGRATION_API.md` | ✅ **已建**（Phase 0 起；Phase 1 补齐四个只读端点，Phase 2 补描述符与鉴权，Phase 3 补播控与事件流）：面向集成方的**契约手册**（本文件 §8 的展开版，含完整字段表与示例） |

---

## 17. 一句话总结

地基已有（Ktor CIO + 字节直通流 + `UnifiedMusicSource` + 统一 `CPMediaId`），
**不需要新架构，需要的是边界纪律**：
对外只讲 `UnifiedMusicSource` 之上的领域模型，绝不透传 Provider 的 raw JSON；
数据面默认关闭、token 强制、cookie 不出本机；
写操作必须回主线程。
端点清单不是设计出来的，是 `MusicApiService` → 领域模型迁移进度的**自然投影**。
