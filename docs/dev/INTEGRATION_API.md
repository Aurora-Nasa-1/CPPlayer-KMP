# 对外集成 API（v1）

> 让第三方软件把 CPPlayer 当成音源与播控目标。适用读者：集成方。
> 本文只写已可用的部分；未实现的端点返回 `404`。

## 1. 端点总览

| 端点 | 方法 | 性质 | 前置开关 |
|---|---|---|---|
| `GET /health` | GET | 存活探测，无 `/api/v1` 前缀，不走令牌 | — |
| `GET /api/v1/meta` | GET | 版本协商 + 能力清单 | 数据面 |
| `GET /api/v1/providers` | GET | 已加载音源 | 数据面 |
| `POST /api/v1/search` | POST | 搜索 | 数据面 |
| `GET /api/v1/tracks/{mediaId}` | GET | 单曲详情 | 数据面 |
| `POST /api/v1/tracks/batch` | POST | 批量详情 | 数据面 |
| `GET /api/v1/playback` | GET | 播放状态快照 | 数据面 |
| `GET /api/v1/playback/lyrics` | GET | 当前歌词（TTML / LRC） | 数据面 |
| `POST /api/v1/playback/{action}` | POST | 播控写操作 | 数据面 + 远程播控 |
| `GET /api/v1/events` | GET | 播放状态 SSE | 数据面 |
| `GET /stream` | GET | 媒体面字节直通 | 媒体面（默认开） |

## 2. 先读：契约边界

| 事实 | 含义 |
|---|---|
| 契约建立在 CPPlayer 的领域模型上 | 端点清单是领域模型迁移进度的投影，不是设计出来的 |
| 先声明后实现比不实现更糟 | 集成方会照文档写代码然后踩空，所以未实现端点返回 `404`，不返回空壳 JSON |
| 必须先握手 | 调 `GET /api/v1/meta` 读 `capabilities`，按能力分支，不照本文猜 |
| 禁用项 | 不暴露上游 raw JSON、不转码、不提供账号类接口，见第 13 节 |

### 2.1 `GET /health`

```json
{ "code": 200, "status": "ok", "streamPort": 8080 }
```

| 项 | 说明 |
|---|---|
| 实现 | 与端口服务同在 `LocalServer.jvm.kt` |
| 鉴权 | 不走令牌 |
| 前缀 | 不走 `/api/v1` |
| `streamPort` | 回显当前流端口，便于多实例排查 |
| 用途 | 自动化脚本 / 开机自启前的 `curl` 探活，与「流输出」设置页的连通性测试同源 |

`404` 与 `403` 的区别：`404` 是端点根本不存在（等升级）；`403` 是端点存在但当前配置下不可用（开关关着）。两者不可混为一谈。

## 3. 接入准备

### 3.1 地址与令牌

数据面与流输出共用同一个端口（默认 `8080`），配置项在「本地服务器输出」设置里。

服务可用时 CPPlayer 会把描述符写到固定路径：

```text
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
| `app` | 固定 `"CPPlayer"`，据此确认文件是本程序写的 |
| `apiVersion` | 与 `meta.apiVersion` 同源，用于版本协商 |
| `baseUrl` | 基地址，不含路径；数据面端点即 `baseUrl + "/api/v1/..."` |
| `token` | 访问令牌（明文） |
| `pid` | 写入时 CPPlayer 的进程号 |
| `updatedAt` | 写入时刻（ISO-8601 带时区偏移） |

| 规则 | 内容 |
|---|---|
| 陈旧判定 | 必须用 `pid`：进程异常退出时来不及删除描述符，文件还在但服务已没了 |
| 写入时机 | 服务确认启动成功后；端口或令牌变更时 |
| 删除时机 | 服务停用或进程退出 |
| 文件不存在 | 等于服务没起来（端口被占用时不写描述符），语义明确 |
| 权限 | 桌面实现已收紧到仅属主可读写（POSIX） |
| 明文令牌 | 令牌本就明文存在同目录的 `cp_player_prefs.properties`；对同用户进程不是新增暴露面，跨用户才是新增风险，权限收紧正是为挡它 |
| 集成方 | 不把 `token` 写进日志 |

Android 没有 `~/.cpplayer`，发现方式退化为「用户在设置页读地址与令牌」。这是发现方式的平台差异，契约本身不变。

### 3.2 三个开关

数据面默认关闭，须由用户在设置页打开。开关即时生效，不需要重启服务。

| 开关 | 持久化键 | 默认 | 作用 |
|---|---|---|---|
| 媒体面 | `local_server_expose_stream` | 开 | `GET /stream` |
| 数据面 | `local_server_expose_data_api` | 关 | `/api/v1/...` |
| 远程播控 | `local_server_allow_remote_control` | 关 | `POST /api/v1/playback/{action}` |

数据面与远程播控是两道独立的门：数据面开着只说明读端点能用；写端点还要再开远程播控。默认值对应最常见的用法：让别的软件读我的音乐，但别动我的播放器。

### 3.3 握手

```bash
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8080/api/v1/meta
```

## 4. 通用约定

### 4.1 前缀与版本

| 约定 | 内容 |
|---|---|
| 前缀 | 所有数据端点 `/api/v1` |
| 演进 | v1 内只做加法：不删字段、不改含义；破坏性变更另开 `/api/v2` 并存一段时间 |
| 协商 | 靠 `meta.apiVersion`，不靠 URL 猜 |
| 请求方向 | 同样只做加法：服务端忽略不认识的字段，客户端先跑不会 400 |

### 4.2 错误形态

数据面错误统一形状：

```json
{ "error": { "code": "face_disabled", "message": "数据面未开放（local_server_expose_data_api = false）" } }
```

`code` 是稳定的机器可读标识，按它分支；`message` 是给人看的，措辞随时可能变，不要解析。

| `code` | HTTP | 含义 | 处理 |
|---|---|---|---|
| `bad_request` | 400 | 请求参数非法 | 改请求 |
| `unauthorized` | 401 | 令牌缺失或不匹配 | 补令牌 |
| `face_disabled` | 403 | 该面未开放（开关关着） | 提示用户去设置页打开 |
| `not_found` | 404 | 目标不存在 | — |
| `unsupported` | 501 | 当前音源不支持该功能 | 换音源，重试无用 |
| `upstream_failed` | 502 | 上游音源失败 | 重试有意义 |
| `internal_error` | 500 | 服务端内部错误 | 上报 |

501 与 502 必须分开处理：501 是能力缺失，不应重复请求；502 才是临时问题，宿主不会自动换音源兜底。混为一谈会导致对不支持该功能的音源无限重试。

例外：媒体面 `/stream` 的既有错误沿用旧形态 `{ "code": 401, "msg": "…" }`，为兼容已对接的接收端保留。

### 4.3 鉴权

| 绑定地址 | 未配置令牌 | 配置了令牌 |
|---|---|---|
| 回环（`127.0.0.1`） | 放行 | 必须携带 |
| 非回环（`0.0.0.0`） | 拒绝（401） | 必须携带 |

| 规则 | 内容 |
|---|---|
| 适用范围 | 媒体面与数据面共用同一条令牌规则（实现是同一个函数），不存在「关掉一个面绕开鉴权」 |
| 局域网 | 不允许裸奔：非回环无令牌一律 401，不可配置关闭 |
| 回环免令牌 | 只在没配令牌时成立；配了令牌后回环也校验 |

两种携带方式（数据面都支持）：

```text
Authorization: Bearer <accessToken>     ← 推荐，日志友好
?token=<accessToken>                    ← 兼容，便于浏览器直接调用
```

`/stream` 只接受 `?token=`，不接受 `Authorization` 头。

### 4.4 响应里不会出现的东西

- Cookie / 会话凭据
- 上游音源的签名 URL
- 模块路径、`apiMap` 等内部信息

需要音频时拿到的永远是本机地址（`http://127.0.0.1:8080/stream?...`），由 CPPlayer 带凭据去上游取字节。

## 5. `GET /api/v1/meta`

版本协商 + 能力清单。应当先调它。

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
| `apiVersion` | int | 数据面版本，当前 `1` |
| `capabilities` | string[] | 真正可用的能力 |
| `provider` | object \| null | 当前活跃音源；没有活跃音源时为 `null`，字段仍出现 |
| `loggedIn` | bool | 当前活跃音源是否已登录；只是布尔，不含凭据 |

| `capabilities` 值 | 含义 |
|---|---|
| `meta` | 本端点 |
| `providers` | 音源列表 |
| `search` | 搜索 |
| `track` | 单曲详情 |
| `trackBatch` | 批量详情 |
| `playback` | 播放状态（只读） |
| `playbackLyrics` | 当前歌词与逐字数据（只读） |
| `playbackControl` | 播控写操作，随 `local_server_allow_remote_control` 变化 |
| `events` | 播放状态事件流（SSE） |
| `stream` | 媒体面可用，随 `local_server_expose_stream` 变化 |

按能力清单分支，不要按本文表格分支：未来新增能力只追加字符串，不破坏已有逻辑。

`stream` 与 `playbackControl` 是配置相关的：开关关掉时它们不在清单里。`playbackControl` 是第二道开关，`exposeDataApi` 开着不代表有写能力。

## 6. `GET /api/v1/providers`

```json
{
  "providers": [
    { "id": "catalog", "name": "Catalog", "version": "1.0.0", "type": "SCRIPT", "active": true }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `id` | 音源标识，`mediaId` 里的命名空间就是它 |
| `name` | 显示名 |
| `version` | 音源版本 |
| `type` | 实现类型，枚举名透传；可安装的 KMP SDK 脚本包为 `SCRIPT`，原生适配器包为 `JNI`；不代表旧协议包可安装 |
| `active` | 是否当前活跃音源，最多一个为 `true` |

## 7. `POST /api/v1/search`

搜索作用于当前活跃音源，跨音源聚合尚未实现。

```json
{ "keywords": "周杰伦", "type": 1, "limit": 20 }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `keywords` | 是 | 全空白会被拒（`400`），不当成搜空字符串 |
| `type` | 否 | 省略为 `1` |
| `limit` | 否 | 本机截断，不是上游分页；省略表示不截断 |

| `type` | 含义 |
|---|---|
| `1` | 单曲 |
| `10` | 专辑 |
| `100` | 歌手 |
| `1000` | 歌单 |

其他值一律 `400 bad_request`，不静默降级成单曲。

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

三个数组总是存在（可能为空），不会因为该类型没结果而省略字段。

| 注意 | 内容 |
|---|---|
| `limit` 语义 | 上游没有分页参数，服务端是「取全量 → 本地 `take(limit)`」；`limit` 只减少响应体大小，不减少上游请求量 |
| 不要当 `page_size` | 否则会误判「只有这么多结果」 |
| `mediaId` 编码 | 含 `://` 与 `/`，放进 URL 必须 URL 编码 |

## 8. `GET /api/v1/tracks/{mediaId}`

`{mediaId}` 是路径参数，必须 URL 编码。

```bash
curl -H "Authorization: Bearer <token>" \
  "http://127.0.0.1:8080/api/v1/tracks/netease%3A%2F%2Fsong%2F12345"
```

响应是一个 `TrackDto`，字段同 7 节的 `songs[]` 元素。

| 情形 | 返回 |
|---|---|
| `mediaId` 为空或格式非法 | `400 bad_request`，本机判定，不触碰上游 |
| 曲目不存在 | `502 upstream_failed` |
| 取数失败 | `502 upstream_failed` |
| 当前音源不支持 | `501 unsupported` |

曲目不存在目前表现为 `502`，不是 `404`：领域层 `getTrackDetail` 把「不存在」与「取数失败」收敛成同一种 `Error`，用例层无从区分。不要按 `404` 分支，会永远走不到；`404` / `not_found` 保留在词汇表里，等领域层补齐错误分类后启用。

只有格式非法能在本机判死（纯函数可判定），这也是唯一能给出精确错误的情形。

## 9. `POST /api/v1/tracks/batch`

单次上限 200 个。

```json
{ "mediaIds": ["netease://song/12345", "netease://song/999", "bogus"] }
```

响应是裸数组（不是对象包裹），顺序与请求完全一致：

```json
[
  { "mediaId": "netease://song/12345", "track": { "...": "..." }, "error": null },
  { "mediaId": "netease://song/999",   "track": null, "error": "上游未返回该曲目（...）" },
  { "mediaId": "bogus",                "track": null, "error": "mediaId 格式非法（...）" }
]
```

| 字段 | 说明 |
|---|---|
| `mediaId` | 回显请求里的原始字符串（不是规范化后的），便于按下标或按值对齐 |
| `track` | 成功时为 `TrackDto` |
| `error` | 失败时为人类可读说明 |

`track` 与 `error` 恰好一个非 null，每项自解释，不必靠下标对齐。

| 语义 | 内容 |
|---|---|
| 单项失败 | 不影响整体：畸形 id、上游未返回都以 `error` 逐项呈现，HTTP 仍是 `200` |
| 顺序 | 与请求一致；重复 `mediaId` 去重后查上游，结果按请求逐项展开 |
| 整批失败 | 也是逐项报；此时拿不到单项信息，所有项统一写「上游未返回」，措辞已声明这层歧义 |
| 上游请求次数 | 一次，不是 N 次（按 500 分片）；逐条查会把批量端点变成第三方可任意放大的代理 |

| 错误 | 返回 |
|---|---|
| `mediaIds` 为空数组 | `400 bad_request` |
| 超过 200 个 | `400 bad_request` |
| 请求体不是合法 JSON | `400 bad_request` |

「上游未返回」不等于「不存在」：上游把「曲目不存在」与「该批请求失败」归为同一结果。需要确定结论时改用第 8 节逐条取。

## 10. `GET /api/v1/playback`

播放状态只读快照。

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
| `positionMs` | 当前位置（毫秒）；快照值，不保证与播放头严格同步 |
| `currentIndex` | 当前曲目在队列中的下标；无曲目为 `-1` |
| `qualityLevel` | 在线音质等级（`standard` / `exhigh` / `lossless` / `hires` …） |
| `currentTrack` | 当前曲目；无曲目为 `null` |
| `queueLength` | 队列长度 |

队列内容不下发，只有长度：队列是 CPPlayer 的内部播放状态，不是音源数据。需要队列内容时用 `tracks/batch` 按 `mediaId` 取。

## 11. `GET /api/v1/playback/lyrics`

当前播放曲目的逐字歌词与音源信息，面向桌面歌词软件与第三方歌词小部件。

```json
{
  "title": "晴天",
  "artist": "周杰伦",
  "album": "叶惠美",
  "durationMs": 269000,
  "isPlaying": true,
  "positionMs": 35000,
  "sourceId": "netease",
  "sourceName": "网易云音乐",
  "hasWordLevel": true,
  "ttml": "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<tt ...>...</tt>",
  "lrc": "[00:00.00]晴天 - 周杰伦\n..."
}
```

| 字段 | 说明 |
|---|---|
| `title` / `artist` / `album` | 当前曲目元数据 |
| `durationMs` | 总时长（毫秒） |
| `isPlaying` | 播放状态 |
| `positionMs` | 当前位置（毫秒） |
| `sourceId` | 原始音源标识（`netease` / `qq` / `amll` / `local`） |
| `sourceName` | 面向用户的音源显示名（`网易云音乐`） |
| `hasWordLevel` | 是否包含逐字时间戳 |
| `ttml` | AMLL / Apple Music 规范的逐字歌词 XML |
| `lrc` | 降级用的标准行级 LRC |

当前无曲目播放时返回 `404 not_found`。

## 12. `POST /api/v1/playback/{action}`

写操作，需打开 `local_server_allow_remote_control`，否则 `403 face_disabled`。

```text
POST /api/v1/playback/play
POST /api/v1/playback/pause
POST /api/v1/playback/next
POST /api/v1/playback/previous
```

请求体为空。路径里的动作名大小写敏感，它是线上契约的一部分。

| 动作 | 含义 |
|---|---|
| `play` | 继续播放 |
| `pause` | 暂停 |
| `next` | 下一首 |
| `previous` | 上一首 |

成功返回 `200`，响应体是动作执行之后的播放状态快照（结构同第 10 节）。服务端等动作真正执行完才返回，集成方不必再打一次 `GET /playback`。

| 状态 | `code` | 场景 |
|---|---|---|
| `403` | `face_disabled` | 未打开远程播控；被拒的请求不会触碰播放器 |
| `400` | `bad_request` | 动作名不认识（如 `shuffle`）；响应列出可用动作 |

`stop` 返回 `400` 并在错误信息里指路 `pause`。播放内核没有非破坏性的停止，只有 `pause()` 与 `clearQueue()`；把 `stop` 映射到 `clearQueue()` 等于一次远程调用静默销毁用户攒的队列且不可撤销。宁可给一个明确的 `400`。

## 13. `GET /api/v1/events`（SSE）

播放状态变化事件流，与其它数据端点同一套鉴权与开关。

```text
GET /api/v1/events?token=<token>
```

`EventSource` 不能自定义请求头，所以本端点必须支持 `?token=`。

```text
event: playback
data: {"isPlaying":true,"positionMs":42000,"currentIndex":0,"qualityLevel":"lossless","currentTrack":{...},"queueLength":2}

: ping

```

| 性质 | 说明 |
|---|---|
| 首帧 | 连上立刻收到当前状态快照，不必先打 `GET /playback` |
| 后续帧 | 每次播放状态变化推一帧，`data` 结构同第 10 节 |
| 心跳 | 每 15 秒一行 `: ping`（SSE 注释行），`EventSource` 会忽略 |
| 连接 | 长连接，不主动结束；客户端断开即回收 |

| 设计点 | 原因 |
|---|---|
| 心跳用注释行 | 它推动 TCP 写出，半开连接（对端已消失但没发 FIN）会在写失败时暴露；没有心跳这种连接会一直挂着 |
| 首帧与变化不丢事件 | 快照与后续变化在同一条流上（靠 `StateFlow` 订阅时的重放拿到首帧），不存在「快照写完、订阅未建立」的空窗；有测试钉住 |
| 不下发队列内容 | 与第 10 节同一条纪律 |

## 14. `GET /stream`（媒体面）

不属于数据面，早于数据面存在，把当前曲目以 HTTP 流转发出去。

```text
GET /stream?mediaId=<URL 编码的 mediaId>&token=<token>
```

| 项 | 内容 |
|---|---|
| `mediaId` 省略 | 输出当前曲目 |
| 字节直通 | 不转码、不重封装；`Range` / `Content-Range` / `Accept-Ranges` / `Content-Length` 原样透传，进度条拖动与断点续传可用 |
| 上游错误 | 如实透传（`403` 防盗链、`404` 失效签名），便于定位 |
| 凭据 | 上游 Cookie 由服务端注入，不下发给调用方 |

`mediaId` 形如 `{providerId}://{resourceType}/{resourceId}`，例如 `netease://song/12345`、`local://audio//abs/path`；含 `://` 与 `/`，必须 URL 编码。

本地曲目走白名单查找（只在已扫描入库的条目里找），`mediaId=local://audio//任意路径` 读不到任意文件。

## 15. 明确不做

| 不做 | 理由 |
|---|---|
| 暴露上游 raw JSON | 契约会被绑死在单个音源实现上 |
| 转码 / 容器重封装 | 字节直通已能拖动进度，转码徒增 CPU 与延迟 |
| 账号类接口（登录 / 扫码 / 发评论） | 远程让人替你登录会放大凭据泄漏面；只读已登录态的数据 |
| 远程 `play-url` | 等于把本机变成任意音频播放器 |
| 远程 `stop` | 内核没有非破坏性停止，只能映射 `clearQueue()`，会静默销毁用户队列 |
| 远程 `seek` / `setVolume` | 影响不可撤销，收益不抵风险 |
| 队列内容下发 | 队列是播放状态不是音源数据；用 `tracks/batch` 取 |
| mDNS / 服务发现广播 | 一个描述符文件足够且零依赖 |

## 16. 版本与兼容承诺

| 承诺 | 内容 |
|---|---|
| v1 内 | 只做加法：新增端点、新增 `capabilities` 字符串、新增响应字段 |
| 已有字段 | 名称与含义不变，不删除 |
| 请求方向 | 忽略未知字段，客户端先跑不报错 |
| 不稳定部分 | `message` 文案与错误码之外的人类可读内容 |
| 破坏性变更 | 开 `/api/v2`，v1 保留一段时间并在 `meta` 标注弃用 |

## 相关

- 架构与数据链：[ARCHITECTURE](ARCHITECTURE.md)
- 用户侧开关说明：[USER_GUIDE 第 5 节](../USER_GUIDE.md#5-外部访问与推流)
- 音源能力来源：[providers/GUIDE](providers/GUIDE.md)
