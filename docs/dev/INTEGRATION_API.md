# CPPlayer 集成 API（v1）

面向**第三方软件**的契约手册：让别的程序把 CPPlayer 当成一个音源来用。

> 设计动机、边界纪律与分阶段计划见 [`INTEGRATION_PLAN.md`](../history/INTEGRATION_PLAN.md)。
> 本文只讲**已经能用**的部分。

---

## 1. 现状（请先读这段）

**v1 目前提供八个数据端点**：六个只读、一个写、一个事件流。

只读：`meta` / `providers` / `search` / `tracks/{mediaId}` / `tracks/batch` / `playback`
写：`POST /playback/{action}` —— 需**另外**打开「远程播控」开关，否则 `403`
事件流：`GET /events` —— SSE，播放状态变化实时推送

对外契约只能建立在 CPPlayer 已有的**领域模型**之上，而领域模型的数量取决于
`MusicApiService` → 领域模型的迁移进度。端点清单是那个进度的**自然投影**，
不是设计出来的。先声明后实现比不实现更糟 —— 集成方会照着文档写代码然后踩空。

所以未实现的端点返回 `404`，**不会**返回空壳 JSON；集成方**必须**先调
`GET /api/v1/meta`，读 `capabilities` 字段决定能用什么，而不是照着本文猜。

| 端点 | 状态 |
|---|---|
| `GET /api/v1/meta` | ✅ 可用 |
| `GET /api/v1/providers` | ✅ 可用 |
| `POST /api/v1/search` | ✅ 可用 |
| `GET /api/v1/tracks/{mediaId}` | ✅ 可用 |
| `POST /api/v1/tracks/batch` | ✅ 可用 |
| `GET /api/v1/playback` | ✅ 可用（只读快照） |
| `POST /api/v1/playback/{action}` | ✅ 可用（需开「远程播控」；`stop` 刻意不做，见 §13） |
| `GET /api/v1/events` | ✅ 可用（SSE） |
| `GET /stream` | ✅ 可用（媒体面，早于数据面就存在） |

> **`404` 与 `403` 的区别**：`404` 表示端点**根本不存在**（还没实现，等升级）；
> `403` 表示端点存在但**当前配置下不可用**（开关关着，引导用户去开开关）。
> 集成方不该把两者混为一谈。

---

## 2. 快速开始

### 2.1 拿到地址与令牌（自动发现）

数据面与流输出**共用同一个端口**（默认 `8080`），配置项在 CPPlayer 的
「本地服务器输出」设置里。

**不要手抄地址和令牌。** 服务可用时 CPPlayer 会把它写到固定路径：

```
~/.cpplayer/integration.json
（Windows 为 %USERPROFILE%\.cpplayer\integration.json）
```

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

| 字段 | 说明 |
|---|---|
| `app` | 固定 `"CPPlayer"`；据此确认文件确实是本程序写的 |
| `apiVersion` | 与 `meta.apiVersion` 同源，用于版本协商 |
| `baseUrl` | **基地址，不含路径**。数据面端点即 `baseUrl + "/api/v1/..."` |
| `token` | 访问令牌（明文，见下方警告） |
| `pid` | 写入时 CPPlayer 的进程号 |
| `updatedAt` | 写入时刻（ISO-8601 带时区偏移） |

**必须用 `pid` 判断陈旧。** 进程异常退出（崩溃、被强杀）时来不及删除描述符 ——
文件还在，服务已经没了。进程不在了就不要拿这份描述符去连。

写入时机：服务**确认启动成功**后 / 端口或令牌变更 / 服务停用或进程退出时删除。
注意是「确认启动成功」：端口被占用时不会写出描述符，所以**文件不存在 = 服务没起来**，
语义是明确的。

> ⚠️ **这个文件含明文令牌。** 桌面实现已把权限收紧到仅属主可读写（POSIX 平台）。
> 另外请注意：令牌本来就明文存在同目录的 `cp_player_prefs.properties` 里，
> 所以对**同用户**进程这不是新增暴露面；**跨用户**才是新增风险，权限收紧正是为了挡它。
> 集成方**不应**把 `token` 写进自己的日志。

> **Android 没有 `~/.cpplayer`。** 发现方式退化为「用户在设置页读地址与令牌」。
> 这是**发现方式**的平台差异，**契约本身不变** —— 同一套 `/api/v1/...` 两端都可用。

### 2.2 三个开关

数据面**默认关闭**，必须先由用户在设置页打开：

| 开关 | 持久化键 | 默认 | 作用 |
|---|---|---|---|
| 媒体面 | `local_server_expose_stream` | **开** | `GET /stream` |
| 数据面 | `local_server_expose_data_api` | **关** | `/api/v1/...` |
| 远程播控 | `local_server_allow_remote_control` | **关** | `POST /api/v1/playback/{action}` |

开关**即时生效**，不需要重启服务。

> **「数据面」与「远程播控」是两道独立的门。** 数据面开着只说明读端点能用；
> 写端点还要**再**开远程播控。这样用户可以「让别的软件读我的音乐，但别动我的播放器」——
> 这是最保守也最常见的用法，所以做成了默认值。

### 2.3 先握手

```bash
curl -H "Authorization: Bearer <token>" \
  http://127.0.0.1:8080/api/v1/meta
```

---

## 3. 通用约定

### 3.1 前缀与版本

- 所有数据端点前缀 `/api/v1`。
- **只做加法**：v1 内不删字段、不改字段含义。破坏性变更另开 `/api/v2`，与 v1 并存一段时间。
- 版本协商靠 `meta.apiVersion`，不要靠 URL 猜。
- 请求方向也**只做加法**：服务端忽略不认识的字段，所以客户端先跑新版本不会报 400。

### 3.2 错误形态

所有**数据面**错误都是同一个形状：

```json
{ "error": { "code": "face_disabled", "message": "数据面未开放（local_server_expose_data_api = false）" } }
```

`code` 是**稳定的机器可读标识**，请按它分支；`message` 是给人看的，措辞随时可能变，
**不要解析它**。

| `code` | HTTP | 含义 | 该怎么办 |
|---|---|---|---|
| `bad_request` | 400 | 请求参数非法 | 改请求 |
| `unauthorized` | 401 | 令牌缺失或不匹配 | 补令牌 |
| `face_disabled` | 403 | 该面未开放（开关关着） | 提示用户去设置页打开 |
| `not_found` | 404 | 目标不存在 | — |
| `unsupported` | **501** | **当前音源**不支持该功能 | **换音源**，重试无用 |
| `upstream_failed` | 502 | 上游音源失败 | **重试**有意义 |
| `internal_error` | 500 | 服务端内部错误 | 上报 |

> **`501` 与 `502` 必须分开处理。** `501` 不是故障，是能力缺失：`apiMap` 把该端点
> 映射成了 `unsupported`，换个音源就能用。`502` 才是网络/上游的临时问题。
> 把两者混为一谈会导致集成方对着不支持该功能的音源无限重试。

> **例外**：媒体面 `/stream` 的既有错误沿用旧形态 `{ "code": 401, "msg": "…" }`，
> 这是为兼容已经对接的接收端保留的，不要指望它符合上面的表。

### 3.3 鉴权

| 绑定地址 | 未配置令牌 | 配置了令牌 |
|---|---|---|
| 回环（`127.0.0.1`） | 放行 | **必须**携带 |
| 非回环（`0.0.0.0`） | **拒绝**（401） | **必须**携带 |

上表**同时适用于媒体面（`/stream`）与数据面（`/api/v1/...`）** —— 两个面共用同一条
令牌规则（实现上是同一个函数），所以不存在「关掉一个面就绕开鉴权」这种缝。

任何一个面都**不允许**在局域网上裸奔 —— 非回环无令牌时一律 401，这条不可配置关闭。
另外注意「回环免令牌」只在**没配**令牌时成立；一旦配了令牌，回环也照样校验。

两种携带方式（数据面都支持）：

```
Authorization: Bearer <accessToken>     ← 推荐，日志友好
?token=<accessToken>                    ← 兼容，便于浏览器直接调用
```

> `/stream` **只**接受 `?token=`，不接受 `Authorization` 头。

### 3.4 响应里永远不会出现的东西

- Cookie / 会话凭据
- 上游音源的签名 URL
- 模块路径、`apiMap` 等内部信息

需要音频时拿到的永远是**本机**地址（`http://127.0.0.1:8080/stream?...`），
由 CPPlayer 负责带上凭据去上游取字节。

---

## 4. `GET /api/v1/meta`

版本协商 + 能力清单。**应当**先调它。

```json
{
  "app": "CPPlayer",
  "apiVersion": 1,
  "capabilities": ["meta", "providers", "search", "track", "trackBatch", "playback", "events", "stream"],
  "provider": { "id": "netease", "name": "NeteaseCloudMusicApi", "version": "1.2.0" },
  "loggedIn": true
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `app` | string | 固定 `"CPPlayer"` |
| `apiVersion` | int | 数据面版本；当前为 `1` |
| `capabilities` | string[] | **真正可用**的能力，见下 |
| `provider` | object \| null | 当前活跃音源；没有活跃音源时为 `null`（字段仍会出现） |
| `loggedIn` | bool | 当前活跃音源是否已登录。**只是布尔，不含任何凭据** |

### `capabilities` 取值

| 值 | 含义 |
|---|---|
| `meta` | 本端点 |
| `providers` | 音源列表端点 |
| `search` | 搜索 |
| `track` | 单曲详情 |
| `trackBatch` | 批量曲目详情 |
| `playback` | 播放状态（只读） |
| `playbackControl` | 播控**写**操作（随 `local_server_allow_remote_control` 变化） |
| `events` | 播放状态事件流（SSE） |
| `stream` | 媒体面可用（随 `local_server_expose_stream` 变化） |

集成方应当**按能力清单分支**，而不是按本文档的表格分支 —— 未来新增能力只追加字符串，
不会破坏已有逻辑。

> `stream` 与 `playbackControl` 是**配置相关**的：开关关掉时它们不在清单里。
> 这两件事对集成方都是硬约束，所以如实反映开关状态，而不是只报实现存在。
>
> ⚠️ `playbackControl` 是**第二道**开关：`exposeDataApi` 开着**不代表**有写能力。
> 只按「数据面能用」就假定能播控，会在写操作上撞 `403`。

---

## 5. `GET /api/v1/providers`

已加载音源列表。

```json
{
  "providers": [
    { "id": "netease", "name": "NeteaseCloudMusicApi", "version": "1.2.0", "type": "JNI", "active": true },
    { "id": "local",   "name": "本地音乐",               "version": "1.0.0", "type": "BINARY", "active": false }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `id` | 音源标识；`mediaId` 里的命名空间就是它 |
| `name` | 显示名 |
| `version` | 音源版本 |
| `type` | 实现类型：`JNI` / `BINARY` / `WEBSOCKET` / `HTTP` |
| `active` | 是否为当前活跃音源；**最多一个**为 `true` |

---

## 6. `POST /api/v1/search`

搜索。**搜索作用于当前活跃音源**（跨音源聚合尚未实现）。

请求：

```json
{ "keywords": "周杰伦", "type": 1, "limit": 20 }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `keywords` | ✅ | 全空白会被拒（`400`），不会当成「搜空字符串」 |
| `type` | — | 省略时为 `1`。取值见下表 |
| `limit` | — | **本机截断**，不是上游分页，见下方警告。省略表示不截断 |

| `type` | 含义 |
|---|---|
| `1` | 单曲 |
| `10` | 专辑 |
| `100` | 歌手 |
| `1000` | 歌单 |

其他值一律 `400 bad_request`（不静默降级成单曲）。

响应：

```json
{
  "songs": [
    {
      "mediaId": "netease://song/12345",
      "name": "晴天",
      "artist": "周杰伦",
      "album": "叶惠美",
      "coverUrl": "https://...",
      "durationMs": 269000,
      "streamUrl": "http://127.0.0.1:8080/stream?mediaId=netease%3A%2F%2Fsong%2F12345&token=..."
    }
  ],
  "playlists": [],
  "artists": []
}
```

三个数组**总是存在**（可能为空数组），不会因为「该类型没结果」而省略字段。

> ⚠️ **`limit` 是本机截断。** 上游搜索接口没有分页参数，所以服务端仍然是
> 「取全量 → 本地 `take(limit)`」。这意味着：
> - `limit` **不能**减少上游请求量，只减少响应体大小；
> - 想要真正的分页/翻页，得等上游能力补齐。
>
> 不要把它当 `page_size` 用，否则会误判「只有这么多结果」。

> `mediaId` 含 `://` 与 `/`，**放进 URL 时必须 URL 编码**（上面示例里是
> `netease%3A%2F%2Fsong%2F12345`）。

---

## 7. `GET /api/v1/tracks/{mediaId}`

单曲详情。`{mediaId}` 是**路径参数**，必须 URL 编码：

```bash
curl -H "Authorization: Bearer <token>" \
  "http://127.0.0.1:8080/api/v1/tracks/netease%3A%2F%2Fsong%2F12345"
```

响应是一个 `TrackDto`（字段同 §6 里的 `songs[]` 元素）。

### 错误语义（重要）

| 情形 | 返回 |
|---|---|
| `mediaId` 为空或格式非法 | `400 bad_request`（**本机判定，不触碰上游**） |
| 曲目不存在 | `502 upstream_failed` ⚠️ |
| 取数失败 | `502 upstream_failed` |
| 当前音源不支持 | `501 unsupported` |

> ⚠️ **曲目不存在目前表现为 `502`，不是 `404`。** 这是已知的契约缺陷：
> 领域层的 `getTrackDetail` 把「不存在」与「取数失败」都收敛成同一种 `Error`，
> 用例层无从区分。要给出 `404` 得先让领域层把这两种结果分开（Phase 4）。
>
> **在那之前不要按 `404` 分支** —— 你会永远走不到那个分支。
> `404` / `not_found` 保留在错误码词汇表里，Phase 4 补齐错误分类后启用。

只有**格式**非法能在本机判死（纯函数可判定），这也是唯一能给出精确错误的情形。

---

## 8. `POST /api/v1/tracks/batch`

批量取详情。单次上限 **200** 个。

请求：

```json
{ "mediaIds": ["netease://song/12345", "netease://song/999", "bogus"] }
```

响应是一个**裸数组**（不是对象包裹），**顺序与请求完全一致**：

```json
[
  { "mediaId": "netease://song/12345", "track": { "...": "..." }, "error": null },
  { "mediaId": "netease://song/999",   "track": null, "error": "上游未返回该曲目（...）" },
  { "mediaId": "bogus",                "track": null, "error": "mediaId 格式非法（...）" }
]
```

| 字段 | 说明 |
|---|---|
| `mediaId` | 回显请求里的**原始**字符串（不是规范化后的），便于按下标/按值对齐 |
| `track` | 成功时为 `TrackDto` |
| `error` | 失败时为人类可读说明 |

**`track` 与 `error` 恰好一个非 null**，每一项都自解释，集成方不必靠下标对齐。

### 语义

- **单项失败不影响整体**：畸形 id、上游未返回，都以 `error` 逐项呈现，HTTP 仍是 `200`。
- **顺序与请求一致**；重复的 `mediaId` 会被去重后再查上游，但**结果里按请求逐项展开**。
- **整批失败也是逐项报**。此时拿不到单项信息，所有项统一写「上游未返回」——
  措辞里已声明这层歧义，不假装知道具体原因。
- **一次上游请求，不是 N 次**：内部走批量接口（按 500 分片）。这是防放大 ——
  逐条查会把批量端点变成第三方可任意放大的代理。

| 错误 | 返回 |
|---|---|
| `mediaIds` 为空数组 | `400 bad_request` |
| 超过 200 个 | `400 bad_request` |
| 请求体不是合法 JSON | `400 bad_request` |

> ⚠️ 「上游未返回」**不等于**「不存在」：上游把「曲目不存在」与「该批请求失败」
> 归为同一结果。需要确定结论时改用 §7 逐条取。

---

## 9. `GET /api/v1/playback`

播放状态**只读快照**。

```json
{
  "isPlaying": true,
  "positionMs": 42000,
  "currentIndex": 3,
  "qualityLevel": "lossless",
  "currentTrack": { "mediaId": "netease://song/12345", "name": "晴天", "...": "..." },
  "queueLength": 12
}
```

| 字段 | 说明 |
|---|---|
| `isPlaying` | 是否正在播放 |
| `positionMs` | 当前播放位置（毫秒）。快照值，不保证与播放头严格同步 |
| `currentIndex` | 当前曲目在队列中的下标；无曲目时为 `-1` |
| `qualityLevel` | 在线音质等级（`standard` / `exhigh` / `lossless` / `hires` …） |
| `currentTrack` | 当前曲目；无曲目时为 `null` |
| `queueLength` | 队列长度 |

> 队列**内容**不下发，只有长度。这是刻意的：队列是 CPPlayer 的内部播放状态，
> 不是音源数据。需要队列内容时用 `tracks/batch` 按 `mediaId` 取。
>
> 播控写操作见 §10；不重连就能拿到状态变化见 §11。

---

## 10. `POST /api/v1/playback/{action}`

**写操作。** 需要 `local_server_allow_remote_control` 打开，否则 `403 face_disabled`。

```
POST /api/v1/playback/play
POST /api/v1/playback/pause
POST /api/v1/playback/next
POST /api/v1/playback/previous
```

请求体：**无**（空体即可）。路径里的动作名**大小写敏感** —— 它是线上契约的一部分。

| 动作 | 含义 |
|---|---|
| `play` | 继续播放 |
| `pause` | 暂停 |
| `next` | 下一首 |
| `previous` | 上一首 |

成功返回 `200`，响应体是**动作执行之后**的播放状态快照（结构与 §9 完全相同）：

```json
{ "isPlaying": true, "positionMs": 42000, "currentIndex": 0, "qualityLevel": "lossless",
  "currentTrack": { "mediaId": "netease://song/12345", "...": "..." }, "queueLength": 12 }
```

> **返回的是动作之后的快照，不是动作之前的。** 服务端会**等动作真正执行完**才返回，
> 所以集成方调完写操作不必再打一次 `GET /playback` 就能刷新界面。

### 错误

| 状态 | `code` | 场景 |
|---|---|---|
| `403` | `face_disabled` | 未打开「远程播控」。**被拒的请求不会触碰播放器** |
| `400` | `bad_request` | 动作名不认识（如 `shuffle`）；响应会列出可用动作 |

### 刻意不做的动作：`stop`

`stop` 返回 `400`，并在错误信息里**指路 `pause`**。这不是遗漏：

CPPlayer 的播放内核**没有非破坏性的「停止」** —— 只有 `pause()` 和 `clearQueue()`。
把 `stop` 映射到 `clearQueue()` 意味着**一次远程调用静默销毁用户攒的播放队列**，
而且不可撤销。宁可让集成方拿到一个明确的 `400` 加一句「请用 pause」，
也不要给它一个会毁数据的「成功」。

> 同理不做 `seek` / `setVolume` / `play-url` / 入队，见 §13。

---

## 11. `GET /api/v1/events`（SSE）

播放状态变化的事件流，**Server-Sent Events**。与其它数据端点同一套鉴权与开关。

```
GET /api/v1/events?token=<token>
```

`EventSource` **不能自定义请求头**，所以这个端点必须支持 `?token=`（见 §3.3）。

```
event: playback
data: {"isPlaying":true,"positionMs":42000,"currentIndex":0,"qualityLevel":"lossless","currentTrack":{...},"queueLength":2}

: ping

```

| 性质 | 说明 |
|---|---|
| 首帧 | **连上立刻收到当前状态快照**，不必先打 `GET /playback` |
| 后续帧 | 每次播放状态变化推一帧，`data` 结构与 §9 相同 |
| 心跳 | 每 15 秒一行 `: ping`（SSE **注释行**），`EventSource` 会忽略它 |
| 连接 | 长连接，不主动结束；客户端断开即回收 |

> **心跳为什么用注释行**：它推动 TCP 写出，于是**半开连接**（对端已消失但没发 FIN）
> 会在写失败时暴露出来。没有心跳的话这种连接会一直挂着，服务端永远不知道要回收。
> 对集成方是无副作用的 —— 注释行不会触发 `onmessage`。

> **首帧与后续变化之间不会丢事件。** 服务端把「快照」与「后续变化」放在**同一条流**上
> （靠 `StateFlow` 订阅时的重放拿到首帧），不存在「快照写完、订阅还没建立」的空窗。
> 这一点有测试钉着（`事件流先发快照再推变化`）。

> 事件流**不下发队列内容**，与 §9 同一条纪律。

---

## 12. `GET /stream`（媒体面）

**不属于数据面**，早于数据面就存在，是 CPPlayer 把当前曲目以 HTTP 流转发出去的端点。

```
GET /stream?mediaId=<URL 编码的 mediaId>&token=<token>
```

- `mediaId` 省略时输出「当前曲目」。
- **字节直通**：不转码、不重封装。`Range` / `Content-Range` / `Accept-Ranges` /
  `Content-Length` 原样透传，所以进度条拖动与断点续传都能正常工作。
- 上游错误（`403` 防盗链、`404` 失效签名）**如实透传**，便于定位。
- 上游凭据（Cookie）由服务端注入，**不下发给调用方**。

`mediaId` 采用 `{providerId}://{resourceType}/{resourceId}` 形式，例如
`netease://song/12345`、`local://audio//abs/path`。它含 `://` 与 `/`，**必须** URL 编码。

> 本地曲目走**白名单查找**（只在已扫描入库的条目里找），
> `mediaId=local://audio//任意路径` **不能**读到任意文件。

---

## 13. 明确不做

| 不做 | 理由 |
|---|---|
| 暴露上游 raw JSON | 契约会被绑死在单个音源实现上 |
| 转码 / 容器重封装 | 字节直通已能拖动进度，转码徒增 CPU 与延迟 |
| 账号类接口（登录 / 扫码 / 发评论） | 远程让人替你登录会放大凭据泄漏面；**只读已登录态的数据** |
| 远程 `play-url`（指定任意 URL 播放） | 等于把本机变成任意音频播放器 |
| 远程 `stop` | 内核没有非破坏性停止，只能映射到 `clearQueue()` —— 会静默销毁用户队列（§10） |
| 远程 `seek` / `setVolume` | 同 `stop`：影响**不可撤销**，收益不抵风险 |
| 队列内容下发 | 队列是播放状态不是音源数据；用 `tracks/batch` 取 |
| mDNS / 服务发现广播 | 一个描述符文件足够且零依赖 |

---

## 14. 版本与兼容承诺

- v1 内**只做加法**：新增端点、新增 `capabilities` 字符串、新增响应字段。
- 已有字段的**名称与含义不变**；字段不会被删除。
- 请求方向忽略未知字段，客户端先跑不会报错。
- `message` 文案、错误码之外的人类可读内容**不保证稳定**。
- 需要破坏性变更时开 `/api/v2`，`v1` 保留一段时间并在 `meta` 里标注弃用。
