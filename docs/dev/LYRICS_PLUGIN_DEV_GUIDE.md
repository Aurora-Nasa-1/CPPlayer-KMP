# CPPlayer 歌词源插件开发指南（Lyrico Plugin API 兼容 / Rhino 宿主）

> **面向：** 为 CPPlayer 开发**歌词源插件**的第三方开发者
>
> **版本：** 1.0 · **最后更新：** 2026-10-29 · **宿主契约：** Host API v4 / 插件 API v1–v5
>
> 本文一切结论以宿主源码为准，文末「[事实来源](#12-事实来源)」给出每条结论的代码锚点。
> 与音源插件（Provider）的 manifest 规范是**两套体系**，音源插件请看 [MANIFEST_REFERENCE.md](MANIFEST_REFERENCE.md)。

---

## 目录

1. [概述](#1-概述)
2. [插件包结构与安装](#2-插件包结构与安装)
3. [manifest.json 规范](#3-manifestjson-规范)
4. [宿主 API（JS 侧可调用的 Platform.*）](#4-宿主-apijs-侧可调用的-platform)
5. [插件脚本契约（searchSongs / getLyrics）](#5-插件脚本契约searchsongs--getlyrics)
6. [生命周期、超时与隔离](#6-生命周期超时与隔离)
7. [完整可运行示例](#7-完整可运行示例)
8. [调试与排错](#8-调试与排错)
9. [与官方来源的关系（取词回退链）](#9-与官方来源的关系取词回退链)
10. [持久化位置与设置键](#10-持久化位置与设置键)
11. [兼容性速查](#11-兼容性速查)
12. [事实来源](#12-事实来源)

---

## 1. 概述

CPPlayer 通过**歌词源插件**接入第三方歌词库。插件是一个 **JavaScript 脚本包**，遵循
[**Lyrico Plugin API**](https://github.com/Replica0110/Lyrico)（Apache-2.0）宿主契约，
由 **Mozilla Rhino**（JVM JS 引擎，Android 与桌面共用同一份实现）执行。

宿主对 Lyrico 契约的态度是**逐字复刻**：`Platform.*` 引导层、全局 `__invoke` 调度器均与上游一致，
因此**按 Lyrico Plugin API 编写的插件无需修改即可导入本宿主**。

### 它解决什么问题

- 内置来源（AMLL 官方词库 / 音源自带歌词 / 本地边车与内嵌歌词）都没有命中时，由用户显式启用的插件补位；
- 为无逐字歌词的曲目接入 TTML / 逐字 LRC 词库（例如内置示例插件接入的 LunaBeat TTML 目录）。

### 与音源插件（Provider）的区别

| 维度 | 歌词源插件（本文） | 音源插件（Provider / Module） |
|---|---|---|
| manifest 数据类 | `cp.player.core.lyricsplugin.PluginManifest` | `cp.player.core.provider.ModuleManifest` |
| 关键字段 | `versionCode` / `entry` / `capabilities = searchSongs｜getLyrics｜searchCovers` | `type` / `entryPoint` / `apiMap` |
| 执行体 | JS（Rhino，Android + 桌面同一实现） | HTTP / JNI / binary |
| 版本编号 | 插件 `apiVersion` 支持区间 **1–5**，宿主 `HOST_API_VERSION = 4` | 宿主 `HOST_PROVIDER_API_VERSION = 3` |
| 管理界面 | 设置 → 账号与音源 → **歌词来源** | 设置 → 账号与音源 → **音源** |

> ⚠️ **两套版本编号互不相干。** `ModuleManifest` 的 KDoc 明确写了这一点（原文提到歌词侧走
> `LyricsPluginManifest.pluginApiVersion` —— 注意该 KDoc 里的类名/字段名与实现不完全一致，
> 实现里实际是 `PluginManifest.apiVersion` 与 `minHostApiVersion`，以代码为准）。
> 不要把音源包的 `apiVersion = 3` 套进歌词插件的 manifest。

### 关键概念

| 术语 | 说明 |
|---|---|
| **PluginManifest** | 插件目录下的 `manifest.json`，Lyrico 兼容 schema（见 §3） |
| **Platform** | JS 侧宿主 API 命名空间（`Platform.http.getText(...)` 等，见 §4） |
| **`__invoke`** | 宿主调用插件入口的全局调度器；插件只需暴露 `globalThis.searchSongs` / `globalThis.getLyrics`（见 §5） |
| **LyricsSourceRegistry** | 统一来源注册表：内置来源与插件同列一张可排序表，顺序即优先级 |
| **capabilities** | manifest 里的能力声明；**未声明 `getLyrics` 的插件永远不会被取词链路调用** |

---

## 2. 插件包结构与安装

### 2.1 ZIP 布局

插件包是一个 `.zip`，宿主会**递归扫描 zip 内所有 `manifest.json`**（任意深度均可），
每个含 manifest 的目录被视为一个插件 —— **一个 zip 可以打包多个插件**。

```
my-lyrics-plugin.zip
└── my-lyrics-plugin/          # 目录名任意，最终以 manifest.id 为准落盘
    ├── manifest.json          # 必需
    ├── source.js              # manifest.entry 指向的入口脚本（缺省即 source.js）
    └── lib/                   # 可选：manifest.includeDirs 声明的目录
        └── util.js            #   该目录下所有 *.js 会按路径序拼在入口脚本之前
```

**打包限制（超出即导入失败）：**

| 限制 | 值 |
|---|---|
| zip 条目数 | ≤ 512 |
| 单个条目解压后大小 | ≤ 16 MiB |
| 整包解压后总大小 | ≤ 64 MiB |
| 路径穿越（zip-slip） | 拒绝 |

### 2.2 安装路径

| 平台 | 插件目录 | 状态文件 |
|---|---|---|
| 桌面（Windows/Linux/macOS） | `~/.cpplayer/lyrics-plugins/<id>/` | `~/.cpplayer/lyrics-plugins/state.json` |
| Android | `<应用私有目录 files>/lyrics-plugins/<id>/` | 同目录 `state.json` |

- `<id>` 是 manifest 的 `id` 经字符清洗后的目录名（`[^A-Za-z0-9._-]` 一律替换为 `_`）。
- `state.json` 存**启用状态**与**各插件的自定义配置**；重新导入同 id 插件会先整目录覆盖。

### 2.3 导入入口

✅ **用户导入已实现**：应用内 **设置 → 账号与音源 → 歌词来源 → 导入歌词源插件（zip）**。

- 桌面端弹系统文件对话框（仅过滤 `.zip`）；Android 走 SAF 选 zip。
- 导入成功后自动重扫来源列表；**新插件默认停用**，需要用户手动打开开关。

### 2.4 内置插件

宿主随应用分发一个内置示例插件 `lunabeat-ttml-hub`
（LunaBeat 开放 TTML 歌词目录，源码在 `core/src/jvmMain/resources/lyrics-plugins/`）：

- 内置插件从 classpath 资源 `lyrics-plugins/index.txt` 列出的目录载入，**不可删除**；
- 同 id 的导入插件**不能覆盖**内置插件（载入时内置优先去重）；
- 内置插件的 `includeDirs` 不能运行时列目录，需在打包时把文件清单展开成 `<dir>/index.txt`
  （导入型插件无此限制，宿主直接遍历目录）；
- 内置插件**默认停用** —— 启用才代表用户同意访问其数据源。

---

## 3. manifest.json 规范

数据类：`PluginManifest`（kotlinx.serialization，`ignoreUnknownKeys = true`，**未知字段被忽略**）。
最小合法 manifest：

```json
{
  "id": "my-lyrics-plugin",
  "name": "My Lyrics",
  "entry": "source.js",
  "capabilities": ["searchSongs", "getLyrics"]
}
```

### 全字段表

| 字段 | 类型 | 必填 | 缺省 | 语义 |
|---|---|---|---|---|
| `id` | String | ✅ | — | 全局唯一标识。落盘目录名、缓存命名空间、启用状态都按它索引 |
| `name` | String | ✅ | — | 展示名（来源列表、播放页歌词来源标签） |
| `versionCode` | Int | 可选 | `0` | 整数版本号 |
| `versionName` | String | 可选 | `""` | 展示版本；为空时管理页显示 `versionCode` |
| `author` | String | 可选 | `""` | 作者，管理页副标题展示 |
| `description` | String | 可选 | `""` | 描述 |
| `apiVersion` | Int | 可选 | `1` | 插件自身实现的**插件 API 版本**，宿主支持区间 **1–5**；越界在载入时拒收（不进列表、不报错弹窗，见 §8） |
| `minHostApiVersion` | Int | 可选 | `1` | 插件要求的**宿主最低 API 版本**，可接受区间 **1–4**（当前宿主 `HOST_API_VERSION = 4`）；越界同样拒载 |
| `entry` | String | 可选 | `"source.js"` | 入口脚本相对插件目录的路径；为空或文件不存在 = 拒载 |
| `includeDirs` | Array\<String\> | 可选 | `[]` | 附加脚本目录；其中全部 `*.js` 按路径序拼在入口**之前**求值。脚本内 `include(path)` 只校验「该路径已声明」，不做事 |
| `icon` | String? | 可选 | `null` | 已解析，但**当前宿主未使用**（管理页不显示图标） |
| `capabilities` | Array\<String\> | 可选 | `[]` | 取值见下表。**缺省按「仅 `searchSongs`」处理** —— 这样的插件永远不会被取词 |
| `configFields` | Array\<Object\> | 可选 | `[]` | 自定义配置项声明（见下）。⚠️ 宿主已解析并透传到来源管理页数据，但**当前 UI 尚未渲染配置表单**，也没有写入入口 —— 插件请求里的 `config` 目前恒为空对象 |

### `capabilities` 取值

| 值 | 语义 | 宿主行为 |
|---|---|---|
| `"searchSongs"` | 实现 `globalThis.searchSongs` | 声明 `getLyrics` 时**必需配套**：取词链路先搜歌再取词 |
| `"getLyrics"` | 实现 `globalThis.getLyrics` | **取词闸门**：未声明则宿主从不调用该插件（`fetchLyricsFrom` 直接返回未命中） |
| `"searchCovers"` | 实现 `globalThis.searchCovers` | ⚠️ **当前无任何宿主代码调用它**，只在管理页显示「搜索封面」能力标签 |

### `configFields` 子结构

```json
{
  "key": "apiKey",
  "title": "API Key",
  "summary": "在 xxx 官网申请",
  "group": "账号",
  "type": "password",
  "required": true,
  "defaultValue": "",
  "options": [{ "value": "a", "label": "A 节点", "summary": "" }]
}
```

| 字段 | 类型 | 缺省 | 说明 |
|---|---|---|---|
| `key` | String | — | 配置键；脚本经 `request.config[key]` 读取 |
| `title` | String | — | 表单标题 |
| `summary` | String? | `null` | 副标题 |
| `group` | String | `""` | 分组名 |
| `type` | String | `"text"` | `text` / `password` / `number` / `switch` / `dropdown` / `textarea` / `markdown` |
| `required` | Boolean | `false` | 必填标记 |
| `defaultValue` | Any | `""` | 默认值 |
| `options` | Array\<Object\> | `[]` | `dropdown` 的候选：`{value, label, summary=""}` |

> ⚠️ 再强调一次：**配置表单 UI 尚未实现**，目前没有任何界面会写入这些配置。
> 插件应能在 `config` 为空的情况下工作（例如内置示例插件就不依赖任何配置）。

---

## 4. 宿主 API（JS 侧可调用的 Platform.*)

### 4.1 调用约定（一般无需手写）

宿主在求值插件脚本前注入引导层，JS 侧直接用高层封装：

```js
var text = Platform.http.getText('https://example.com/api');
```

底层链路是：`Platform.* → __lyricoHostCall(name, payloadJson) → 宿主返回 {"value": ...} → 取 .value`。
因此宿主 API 的返回值里**不存在异常通道** —— 失败一律编码进返回值（空串 / null / 空数组）。

> ⚠️ **未注册的 API 不可用。** 宿主只实现了下表列出的调用名；调用其它名字
> （包括 Lyrico 完整清单里的 `xml.*` 与 AES 系列 `crypto.aes*`）返回 **null** 并记一条
> 告警日志，**不会**抛异常 —— 插件要自己对 null 做判空，否则会连锁出类型错误。

### 4.2 app / runtime / i18n

| JS 签名 | 返回 | 说明 |
|---|---|---|
| `Platform.app.getInfo()` | `{name: "CPPlayer", version: "1.0.0"}` | 宿主名与版本（当前实现固定值） |
| `Platform.app.getUserAgent()` | `"CPPlayer/1.0"` | 字符串 |
| `Platform.runtime.getInfo()` | `{host: "CPPlayer", hostApiVersion: 4}` | `hostApiVersion` 取自宿主常量 |
| `Platform.i18n.getLocale()` | `"zh-CN"` | ⚠️ **硬编码**，不随系统/应用语言变化 |
| `Platform.i18n.t(key, ...args)` | `key` 原样返回 | ⚠️ 无翻译表的桩实现，不要依赖它做界面文案 |

`app` / `runtime` 也分别以 `globalThis.app` / `globalThis.runtime` 暴露（同一对象）。

### 4.3 http

| JS 签名 | 返回 | 失败时 |
|---|---|---|
| `Platform.http.getText(url, options?)` | String（响应体文本） | `""` |
| `Platform.http.postText(url, body, options?)` | String | `""` |
| `Platform.http.get(url, options?)` | 响应体 JSON.parse 后的值 | `null`（含「响应不是 JSON」） |
| `Platform.http.post(url, body, options?)` | 同上 | `null` |
| `Platform.http.getBytes(url, options?)` | `number[]`（字节数组） | `[]` |

`options` 字段（全部可选）：

| 字段 | 类型 | 缺省 | 说明 |
|---|---|---|---|
| `headers` | Object | `{}` | 附加请求头；在默认头**之后**设置，同名头会**覆盖**默认值 |
| `contentType` | String | `"application/json; charset=utf-8"` | 仅 POST 生效 |
| `connectTimeoutMs` | Number | `8000` | 连接超时 |
| `readTimeoutMs` | Number | `10000` | 读超时 |
| `followRedirects` | Boolean | `true` | 跟随重定向 |

行为细节：

- **只允许 `http://` / `https://`**；`file://` 等其它 scheme 直接返回失败（防止插件读本地文件）。
- 默认请求头：`User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120.0.0.0 Safari/537.36 CPPlayer/1.0`、
  `Accept: */*`、`Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`、`Accept-Encoding: gzip, deflate`
  （裸 Java UA 会被各大音乐服务 403，这是反爬默认值；`options.headers` 可以覆盖同名头）。
- 301/302/303/307/308 由宿主**手工跟随**，最多 3 跳，支持 http↔https 跨协议跳；303 改 GET。
- `gzip` / `deflate` 响应自动解压。
- ⚠️ **状态码不暴露给 JS**：非 2xx 也照样返回响应体。需要判错误请从内容判断（例如解析失败、
  或像内置示例那样先取 manifest 校验 `schemaVersion`）。
- 任意网络异常（DNS、超时、TLS……）都被吞掉，按上表返回失败值。

### 4.4 cache

| JS 签名 | 返回 | 说明 |
|---|---|---|
| `Platform.cache.get(key)` | String，未命中 `""` | 先查内存表，再查磁盘 |
| `Platform.cache.set(key, value, ttlMs?)` | null | ⚠️ **`ttlMs` 当前被忽略**，写入即永不过期（直至 remove/clear） |
| `Platform.cache.remove(key)` | null | |
| `Platform.cache.clear()` | null | 清空**本插件**的全部缓存 |

命名空间隔离：

- 内存缓存按插件实例隔离（每个插件一个宿主对象）；
- 磁盘缓存文件名 = `<pluginId>-<sha256(key) 前 32 位>`，落在插件缓存根目录下。
- ⚠️ **当前生产装配未向插件服务传磁盘缓存目录**（应用内创建服务时只传了 context），
  因此桌面/Android 实际只有**进程内内存缓存** —— 应用重启即失。
  像内置示例插件那样依赖缓存跨会话的场景，要把「冷启动无缓存」当作常态来设计。

### 4.5 crypto / base64 / bytes / compression

| JS 签名 | 返回 |
|---|---|
| `Platform.crypto.md5(text)` | 十六进制摘要 String |
| `Platform.crypto.sha256(text)` | 十六进制摘要 String |
| `Platform.base64.encodeText(text)` / `decodeText(base64)` | String |
| `Platform.base64.encodeBytes(number[])` | base64 String |
| `Platform.base64.decodeBytes(base64)` | `number[]` |
| `Platform.base64.encodeUrlText(text)` / `decodeUrlText(base64Url)` | URL-safe base64 |
| `Platform.base64.encodeUrlBytes(number[])` / `decodeUrlBytes(base64Url)` | 同上 |
| `Platform.bytes.xor(number[], key[])` | `number[]`（key 循环使用；key 为空原样返回） |
| `Platform.bytes.xorBase64(base64, key[])` | 先解 base64 再 XOR，返回 `number[]` |
| `Platform.compression.inflateBytesToText(number[])` | String（⚠️ **raw zlib inflate，不是 gzip**） |
| `Platform.compression.inflateBase64ToText(base64)` | 同上 |

所有解码失败返回空串 / 空数组，不抛异常。

### 4.6 log

| JS 签名 | 说明 |
|---|---|
| `Platform.log.debug(tag?, message)` | 单参数时 tag 取 `"PlatformPlugin"` |
| `Platform.log.warn(tag?, message)` | 同上 |
| `Platform.log.error(tag?, message)` | 同上 |

> ⚠️ **当前生产装配没有接日志出口**：宿主创建插件宿主对象时使用默认的空 log sink，
> 插件日志（含「Unsupported host call」告警）在桌面与 Android 都**不会出现在任何日志里**。
> 插件自身的排错手段见 §8。

---

## 5. 插件脚本契约（searchSongs / getLyrics)

### 5.1 入口与求值模型

- 插件脚本在**一个常驻 Rhino 运行时**里求值一次；宿主通过全局调度器调入口：

  ```js
  // 宿主注入的调度器（逐字）：
  globalThis.__invoke = function(name, requestJson) {
    var fn = globalThis[name];
    if (typeof fn !== "function") return "null";
    var result = fn(JSON.parse(requestJson || "{}"));
    return JSON.stringify(result === undefined ? null : result);
  };
  ```

- 因此插件只需在**全局**挂函数：

  ```js
  globalThis.searchSongs = function (request) { /* ... */ return [...]; };
  globalThis.getLyrics = function (request) { /* ... */ return {...}; };
  ```

- 宿主把入口返回值 `JSON.stringify` 后再解析；**返回 `undefined` 视为 null（未命中）**；
  入口不存在同样按未命中处理，不会报错。
- JS 能力：**ES6**（Rhino `VERSION_ES6`，解释执行）。不要依赖 Node API、`fetch`、浏览器 DOM。

### 5.2 `searchSongs(request)` —— 搜歌

**宿主发出的请求**（字段名与 Lyrico 一致）：

```json
{
  "keyword": "晴天 周杰伦",
  "page": 1,
  "pageSize": 10,
  "separator": "/",
  "config": {},
  "mediaId": "netease://song/186016"
}
```

- `keyword` = `标题 + 空格 + 歌手`（空白项省略）。宿主会**先用清洗后的关键词**搜
  （`MusicSearchSanitizer`：去文件扩展名、去音轨序号前缀、去 `[FLAC]`/`(Live)` 类音质/版本标记、
  多歌手取第一个、尝试拆分「歌手 - 歌名」），**无命中且原词不同则用原词再搜一次**。
- `page` / `pageSize` 当前由宿主**写死为 1 / 10**（插件仍应按请求值分页，勿硬编码）。
- `config`：插件自定义配置（当前恒为 `{}`，见 §3）。
- `mediaId`：当前曲目的统一标识（`{providerId}://{type}/{id}`），可能为 `null`。

**返回**：JSON 数组，或 `{ "items" | "results" | "songs" | "data": [...] }`。每条候选的字段
（**宽容解析**，同义名任选其一；解析不出的条目被跳过，不抛异常）：

| 目标字段 | 接受的键名 | 说明 |
|---|---|---|
| `id`（**必填**） | `id` / `songId` / `trackId` | 没有 id 的条目**整条丢弃** |
| `title` | `title` / `name` / `songName` | |
| `artist` | `artist` / `artists` / `singer` | 数组会被 `/` 连接 |
| `album` | `album` / `albumName` | |
| `duration` | `duration` / `durationMs` / `duration_ms` | **毫秒**，打分器用它比对曲目时长 |
| `picUrl` | `picUrl` / `coverUrl` / `cover_url` / `artworkUrl` | ⚠️ 已解析，但统一来源链路当前**不消费**封面 |
| `date` | `year` / `date` / `releaseDate` / `release_date` | |
| `trackNumber` | `trackNumber` / `trackerNumber` / `track_number` | |
| `fields` | `fields` / `metadata` | 附属元数据，会透传回 `getLyrics` 的 `song.fields` |
| `internal` | `internal` | **插件私有透传数据**：原样回传给 `getLyrics` 的 `song.internal`。限 ≤64 键、键 ≤64 字符、值 ≤4096 字符，超限被裁剪 |

**宿主选歌**：拿到候选后**不由插件决定哪条命中** —— 宿主用 `LyricsMatchScorer` 按
歌名 / 歌手 / 专辑 / 时长综合打分（含伴奏 / 翻唱 / Live 等负向惩罚），选最高置信度的一条；
全部不达标视为**未命中**（交给下一条来源）。所以插件应：

- 返回**尽量多的合理候选**（宿主会替你挑），而不是自作主张只返回一条；
- 填准 `duration` —— 它是最重要的消歧信号之一。

### 5.3 `getLyrics(request)` —— 取词

**宿主发出的请求**：

```json
{
  "song": {
    "id": "42",
    "title": "晴天", "artist": "周杰伦", "album": "叶惠美",
    "date": "2003", "duration": 269000,
    "sourceId": "my-lyrics-plugin", "pluginId": "my-lyrics-plugin",
    "fields": { "language": "zh" },
    "internal": { "path": "lyrics/42.ttml", "sha256": "…" },
    "mediaId": "netease://song/186016"
  },
  "page": 1, "pageSize": 20,
  "config": {},
  "mediaId": "netease://song/186016"
}
```

`song` 即 `searchSongs` 返回的那条命中（含你塞的 `fields` / `internal`）。

**返回**：四种可接受的形状 ——

| 形状 | 含义 |
|---|---|
| `"[00:01.00]..."` （纯字符串） | 按 plain LRC 解析 |
| `null` / `{ "notFound": true }` | 未命中（下一条来源继续） |
| `[ {...}, {...} ]` （数组） | API 4 的多候选：宿主**取第一个可解析出内容的项** |
| `{ "type": "...", ... }` （对象） | 主路径，见下 |

对象的 `type` 与负载字段（type 大小写不敏感，别名如下）：

| `type` | 别名 | 负载字段（别名） | 宿主处理 |
|---|---|---|---|
| `structured`（缺省） | — | `original` / `translated` / `romanization` | 结构化行直接映射（见下） |
| `rawPlainLrc` | `raw_plain_lrc` / `plainLrc` / `plain_lrc` / `lrc` | `rawPlainLrc`（别名同左 + `originalLrc`/`original_lrc`/`lrc`） | 行级 LRC 解析器 |
| `rawVerbatimLrc` | `raw_verbatim_lrc` | `rawVerbatimLrc` / `raw_verbatim_lrc` | 逐字 LRC 解析器（保留词级时间戳） |
| `rawEnhancedLrc` | `raw_enhanced_lrc` | `rawEnhancedLrc` / `raw_enhanced_lrc` | 同上 |
| `rawTtml` | `raw_ttml` / `ttml` | `rawTtml` / `raw_ttml` | TTML 解析器 |
| `rawMultiPersonEnhancedLrc` | `raw_multi_person_enhanced_lrc` | `rawMultiPersonEnhancedLrc` / `raw_multi_person_enhanced_lrc` | 逐字 LRC 解析器 |

通用可选字段：`tags`（对象，如 `{ti, ar, al}`，原样保留）。

**structured 负载的行格式**（时间单位：**毫秒**）：

```json
{
  "type": "structured",
  "original":      [[1000, 2000, [[1000, 1500, "你"], [1500, 2000, "好"]]]],
  "translated":    [[1000, 2000, "Hello"]],
  "romanization":  [[1000, 2000, [[1000, 2000, "ni hao"]]]]
}
```

- 每行 = `[startMs, endMs, words]`；`words` 可以是**逐字数组** `[[wordStart, wordEnd, text], ...]`，
  也可以是**整行字符串**（`original` / `romanization` 两种形态都收；`translated` 只收字符串形态）。
- `endMs` 缺省等于 `startMs`；`original` 解析不出任何行 = 未命中。
- 翻译 / 罗马音按**时间就近 ±1500ms** 与原文行配对；配不上的自动丢弃。
- 行文本为空的行被丢弃；最终行数为 0 = 未命中。

### 5.4 能力声明与调用的对应关系

| manifest `capabilities` | 宿主行为 |
|---|---|
| 含 `getLyrics` | 正常参与取词（前提：同时实现并能被 `searchSongs` 找到候选） |
| 只含 `searchSongs`（或缺省） | **永不参与取词**，仅在管理页可见 |
| 含 `searchCovers` | 只显示能力标签（宿主暂无调用方） |

---

## 6. 生命周期、超时与隔离

### 6.1 运行时生命周期

- **每插件一个常驻 Rhino 运行时**，首次取词时惰性创建（创建即求值整份脚本：include 拼在前、入口在后），
  之后复用，不会每次调用重新求值。
- 删除插件 → 关闭并销毁其运行时；应用退出 → 全部运行时随服务关闭。
- 重新导入同 id 插件后，**正在运行的旧脚本不会热替换**（运行时缓存按 id 驻留）；管理页删除再导入可强制刷新。

### 6.2 线程模型

- Rhino 运行时**非线程安全**：所有插件求值/调用收敛到一个**专属单线程**
  （线程名 `cpplayer-lyrics-plugin`，栈 4 MiB）。
- 因此**多个插件也是串行执行的**，脚本里无需考虑并发，也别指望并行提速。
- JS 以**解释模式**运行（Rhino `optimizationLevel = -1`），兼容 Android，无字节码生成。

### 6.3 超时

| 层 | 限制 |
|---|---|
| JS 执行 | ⚠️ **没有硬性执行超时 / 指令数上限**。死循环插件会占住插件线程，拖住整条取词链（宿主对象里有预留的取消标记，但当前无人置位）。插件作者务必给所有等待都经 `Platform.http` 并设置合理的 `connectTimeoutMs` / `readTimeoutMs` |
| HTTP | 默认连接 8s / 读 10s；重定向最多 3 跳 |

### 6.4 异常隔离

- 插件入口抛异常、返回非法 JSON、求值失败 —— 一律被宿主吞掉并视为**未命中**，
  **不影响播放，也不影响其它来源**（两层防护：插件服务一层、歌词引擎对每个来源再包一层）。
- 协程取消（`CancellationException`）不被吞，正常向上传播。
- 插件解析器是**宽容解析**：脏条目跳过、缺字段给缺省、绝不抛异常。

### 6.5 存储命名空间

| 数据 | 位置 |
|---|---|
| 启用状态 + 插件配置 | `<dataDir>/lyrics-plugins/state.json`（`enabledIds` / `configs[id][key]`） |
| 来源排序 + 停用表 | `<dataDir>/lyrics-sources/sources.json`（`order` / `disabled` / `configs`） |
| 插件脚本缓存 | 见 §4.4（当前生产仅内存） |

> ⚠️ 启用状态有**两份**记录：`sources.json` 的 `disabled` 决定**调度**（统一来源体系只取未停用的来源），
> 插件服务 `state.json` 的 `enabledIds` 服务于旧链路；设置页开关会同时写两处，正常不会分叉。

---

## 7. 完整可运行示例

以内置的 `lunabeat-ttml-hub` 为蓝本（它演示了 `http.getText` / `cache.*` / `crypto.sha256` /
`log.warn` 与 `rawTtml` 返回），下面写一个**最小但完整**的插件：假设有一个静态歌词站
`https://lyrics.example.com`，搜索接口 `GET /search?q=` 返回歌曲数组，歌词接口
`GET /lyric/<id>` 返回 LRC 文本。

### 7.1 manifest.json

```json
{
  "id": "example-lrc-hub",
  "name": "Example LRC Hub",
  "versionCode": 1,
  "versionName": "1.0.0",
  "author": "You",
  "description": "示例静态 LRC 词库",
  "apiVersion": 3,
  "minHostApiVersion": 1,
  "entry": "source.js",
  "capabilities": ["searchSongs", "getLyrics"]
}
```

`apiVersion` 填 3 或 1 均可（宿主接受 1–5）；`minHostApiVersion` 填 1（接受 1–4）。

### 7.2 source.js

```js
'use strict';

(function () {
  var API = 'https://lyrics.example.com';

  globalThis.searchSongs = function (request) {
    var keyword = String((request && request.keyword) || '');
    if (!keyword) return [];
    var data = Platform.http.get(API + '/search?q=' + encodeURIComponent(keyword));
    // http.get 失败 / 非 JSON → null；数组形态直接可用
    var songs = Array.isArray(data) ? data : (data && data.songs) || [];
    return songs.map(function (song) {
      return {
        id: String(song.id || ''),
        title: String(song.title || ''),
        artist: String(song.artist || ''),
        album: String(song.album || ''),
        duration: Number(song.durationMs || 0),   // 毫秒！打分器要靠它消歧
        internal: { lyricId: String(song.id || '') }  // 透传给 getLyrics
      };
    }).filter(function (song) { return song.id !== ''; });
  };

  globalThis.getLyrics = function (request) {
    var song = request && request.song;
    var lyricId = song && song.internal && song.internal.lyricId;
    if (!lyricId) return null;                       // 未命中
    var lrc = Platform.http.getText(API + '/lyric/' + encodeURIComponent(lyricId));
    if (!lrc) return { notFound: true };
    return {
      type: 'rawPlainLrc',                           // 也可直接 return lrc 字符串
      rawPlainLrc: lrc,
      tags: { ti: String(song.title || ''), ar: String(song.artist || '') }
    };
  };
})();
```

要点：

- **入口必须挂在 `globalThis`** 上（宿主按名字找全局函数）；
- 未命中返回 `null` 或 `{notFound: true}`，**不要抛异常**表达未命中
  （抛异常宿主也吞，但日志语义上是错误）；
- `internal` 是搜歌 → 取词之间的私有透传通道（大小受限，见 §5.2），别塞大对象；
- 需要逐字效果就把 `type` 换成 `rawTtml` / `rawEnhancedLrc` 并返回对应原文，
  或直接用 `structured` 返回词级时间戳（§5.3）。

### 7.3 打包与安装

```
example-lrc-hub/
├── manifest.json
└── source.js
```

```bash
# 在 example-lrc-hub 的父目录执行（manifest 在 zip 里任意深度都能被找到）
zip -r example-lrc-hub.zip example-lrc-hub/
```

安装：应用内 **设置 → 账号与音源 → 歌词来源 → 导入歌词源插件（zip）**，选中 zip。
导入成功后它出现在来源列表**末尾、且处于停用状态** —— 打开开关，按需用 ↑/↓ 调到想要的位置。

### 7.4 验证被选中

播放一首只有你的词库有的歌，播放页歌词信息会显示来源为 manifest 的 `name`，
格式标签为 `Plugin`（行级）或 `Plugin (Karaoke)`（含逐字时间戳）。

---

## 8. 调试与排错

### 8.1 日志入口（现状）

| 通道 | 现状 |
|---|---|
| `Platform.log.*` | ⚠️ 当前宿主**未接日志 sink**，插件日志被丢弃（桌面/Android 皆然） |
| 宿主「Unsupported host call」告警 | 同上 |
| 播放页歌词来源标签 | **最可靠的在线信号**：来源名 = 插件 `name`，格式 = `Plugin` / `Plugin (Karaoke)` |

实用排错法：把脚本核心逻辑抽成纯函数，在本地用任意 JS 引擎 + mock 的 `Platform` 跑单测；
或参考宿主自带的端到端测试（`core/src/desktopTest/.../LyricsPluginTest.kt`）的模式，
直接用 `RhinoPluginRuntime(JvmPluginHostApi(...))` 在 JVM 上复现宿主契约 —— 这是当前唯一能看到
插件日志与精确返回值的方式。

### 8.2 常见失败清单

| 症状 | 可能原因 | 判据 / 解法 |
|---|---|---|
| 导入即失败（「文件不是有效的插件包」） | zip 里没有 `manifest.json`；JSON 不合法（注释/尾逗号/单引号）；`apiVersion` / `minHostApiVersion` 越界；`entry` 缺失或文件不存在；超出 §2.1 的大小限制 | 先过一遍 JSON 校验器；核对版本区间 1–5 / 1–4；`apiVersion: 99` 这类越界**连列表都进不去**，且是静默的 |
| 导入成功但取词从不调用它 | `capabilities` 未声明 `getLyrics`（缺省按「仅搜索」处理） | manifest 加 `"capabilities": ["searchSongs", "getLyrics"]` |
| 插件没被执行 | 开关没打开；被排序位置之前的来源已命中 | 管理页确认开关与顺序（首个命中即胜出，后面的来源根本不会被问） |
| 搜索有结果但整体未命中 | 候选缺 `id`（被整条丢弃）；打分器全部不达标（时长/歌名差太多）；`internal` 超限被裁剪 | 给候选填准 `id` 与毫秒 `duration` |
| `getLyrics` 返回了但显示无歌词 | 返回对象里负载字段名不在别名表内；structured 的 `original` 为空或行文本全空；raw 文本为空 | 对照 §5.3 的表核字段名 |
| HTTP 全是空串 | URL 非 http/https；目标 403/超时；跨协议重定向超过 3 跳 | 检查 URL；必要时显式 `headers` 覆盖 UA；超时默认 8s/10s 可调 |
| 改了 zip 重新导入但行为没变 | 运行时按 id 驻留，旧脚本未释放 | 管理页删除该插件（会关闭运行时）再导入 |
| 一切正常但 `config` 是空的 | 配置表单 UI 尚未实现（见 §3） | 插件应设计为无配置可用 |

### 8.3 验证来源排序 / 回退链

1. 管理页列表**自上而下就是取词顺序**（关闭的来源直接跳过）；
2. 新导入的插件自动追加在列表**末尾**，不会插到内置来源之前；
3. 想确认插件被问到：把它拖到最顶，播放一首已知的歌 —— 来源标签应变更为插件名。

---

## 9. 与官方来源的关系（取词回退链）

生产取词走**统一来源体系**：`LyricsEngine` 按用户在「歌词来源」页排定的顺序逐个询问
**已启用**且声明了取词能力的来源，**首个命中即胜出**。

默认顺序（首次安装 / 旧配置迁移时写入）：

```
本地边车歌词 → 音频内嵌歌词 → AMLL 官方词库 → 音源自带歌词 → [插件…追加在后]
```

插件的位置由用户排序决定，但有三条不变量：

1. **插件默认全部停用（含内置的 lunabeat-ttml-hub）**；未启用的插件不参与调度，
   **不产生任何网络请求** —— 启用动作本身就是用户对「访问该数据源」的授权；
2. 插件**串行**参与回退链（不做并发竞速 —— Rhino 运行时非线程安全，且竞速会让同一首歌
   两次显示不同来源的词）；
3. 插件永远只是**来源之一**：它不会盖过用户排在前面的 AMLL / 音源 / 本地歌词。

> 历史路径备查：在未装配注册表的最小环境（主要是测试）里存在一条旧回退链
> （AMLL → 音源 API → 插件兜底），插件在其中固定位于 AMLL 与音源 API **之后**。
> 应用本体总是装配注册表，正常用户走不到这条路径。

---

## 10. 持久化位置与设置键

| 内容 | 位置 / 键 | 说明 |
|---|---|---|
| 来源顺序 + 停用表 + 来源配置 | `<dataDir>/lyrics-sources/sources.json`（`version=3` / `order` / `disabled` / `configs`） | 统一来源体系的唯一事实源 |
| 插件启用状态 + 插件自定义配置 | `<dataDir>/lyrics-plugins/state.json`（`enabledIds` / `configs`） | 插件服务自管 |
| 插件文件 | `<dataDir>/lyrics-plugins/<id>/` | 导入型插件；内置的在 classpath |
| 旧三档来源设置 | 设置键 `lyrics_source_mode` | **仅一次性迁移读取**（`sources.json` 缺失时换算成新顺序），之后不再写入、也不删除 |
| 桌面 `<dataDir>` | `~/.cpplayer/` | |
| Android `<dataDir>` | 应用私有 `files` 目录 | |

---

## 11. 兼容性速查

| 契约点 | 值 |
|---|---|
| 插件 API 版本（manifest `apiVersion`） | 接受 **1–5** |
| 宿主 API 版本（`runtime.getInfo().hostApiVersion`） | **4** |
| 插件要求的宿主版本（manifest `minHostApiVersion`） | 接受 **1–4** |
| JS 语法 | ES6（Rhino 解释执行） |
| 歌词格式 | TTML / plain LRC / 增强（逐字）LRC / 多人增强 LRC / structured |
| 时间单位 | **毫秒**（`duration`、`start`/`end` 一律毫秒） |
| 与 Lyrico 的差异 | 未实现 `xml.*` 与 AES 系列；i18n 是桩实现；cache TTL 被忽略；日志无出口 |

---

## 12. 事实来源

| 结论 | 代码锚点 |
|---|---|
| manifest 全字段与缺省 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/PluginManifest.kt` L21-36 |
| capabilities 三值；缺省按仅 searchSongs | 同上 L38-49；`LyricoLyricsPluginService.kt` L61、L108、L137-138 |
| `configFields` 子结构与类型枚举 | `PluginManifest.kt` L51-98 |
| 版本常量 1–5 / HOST_API_VERSION=4 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/HostApiRegistry.kt` L16-25 |
| 已实现的 Platform 调用名集合；xml/AES 未实现 | 同上 L28-66 |
| 两套独立版本编号的 KDoc | `core/src/commonMain/kotlin/cp/player/core/provider/ModuleManifest.kt` L89-104（L91-92）；`HOST_PROVIDER_API_VERSION = 3` L104 |
| JS 引导层全部 Platform.* 签名 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/RhinoPluginRuntime.kt` L127-283 |
| `__invoke` 调度器与入口约定 | 同上 L55-64、L115-124 |
| Rhino 解释模式 / ES6 / 非线程安全 | 同上 L40-41、L71-83 |
| `{"value": ...}` 返回包装 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/JvmPluginHostApi.kt` L33-38、L165 |
| i18n 桩（zh-CN / 原样返回 key） | 同上 L70-71 |
| app.info / userAgent / runtime.info | 同上 L61-69 |
| cache TTL 被忽略；命名空间 `<pluginId>-<sha256前32位>` | 同上 L261-263、L282-283 |
| http 行为（scheme 白名单 / 默认头 / 超时 / 重定向 ≤3 / gzip / 状态码不暴露 / 失败吞掉） | 同上 L172-237、L317-319 |
| 未注册 API → null + 告警 | 同上 L160-163 |
| log sink 默认空实现；生产装配未传 sink / 未传 cacheDir | 同上 L44；`LyricoLyricsPluginService.kt` L41-44；`core/src/commonMain/kotlin/cp/player/core/MusicBackend.kt` L434-436 |
| 预留取消标记未使用 | `JvmPluginHostApi.kt` L50-56 |
| 单线程调度器（线程名 / 4MiB 栈）、每插件常驻运行时 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/LyricoLyricsPluginService.kt` L31-46 |
| 关键词清洗 + 二次原词重试；page=1/pageSize=10 | 同上 L156-179；`core/src/commonMain/kotlin/cp/player/core/util/MusicSearchSanitizer.kt` L10-98 |
| 打分器选候选 | `LyricoLyricsPluginService.kt` L182-199 |
| 请求/响应模型（searchSongs / getLyrics / song / 结果） | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/PluginModels.kt` L17-76 |
| 宽容解析：字段别名表、internal 限长、多候选取第一、±1.5s 配对、行格式 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/LyricsPluginJsonParser.kt` L31-131、L192-233 |
| payload type 与别名、raw 交给宿主解析器 | 同上 L140-157、L263-272 |
| 异常隔离（服务层 + 引擎层） | `LyricoLyricsPluginService.kt` L110-118、L139-143；`core/src/commonMain/kotlin/cp/player/core/lyrics/LyricsEngine.kt` L32-42 |
| 取词主链路（引擎 + resolveToState + 格式标签 Plugin/Plugin (Karaoke)） | `LyricsEngine.kt` L23-69；`core/src/commonMain/kotlin/cp/player/core/playback/PlaybackControllerImpl.kt` L1361-1378 |
| 旧回退链：插件在 AMLL 与音源之后兜底；未启用零网络请求 | `PlaybackControllerImpl.kt` L1388-1470；`MusicBackend.kt` L349-350 |
| 注册表：sources.json、默认顺序、迁移、追加在后 | `core/src/jvmMain/kotlin/cp/player/core/lyrics/FileLyricsSourceRegistry.kt` L44-56、L144-155、L186-219；`core/src/commonMain/kotlin/cp/player/core/lyrics/LyricsSource.kt` L116-126 |
| 插件适配为 LyricsSource（mediaId 透传） | `core/src/jvmMain/kotlin/cp/player/core/lyrics/PluginLyricsSource.kt` L24-54 |
| zip 导入：递归找 manifest、多插件、大小限制、内置优先 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/FileLyricsPluginStore.kt` L55-144、L227-259、L271-275 |
| manifest 校验（apiVersion / minHostApiVersion / entry） | 同上 L216-225 |
| state.json（启用 + 配置） | 同上 L34-51、L160-175 |
| 内置插件：index.txt、不可删、include 需打包清单、默认停用 | 同上 L26-27、L66-98、L146-156；`core/src/desktopTest/kotlin/cp/player/core/lyricsplugin/LyricsPluginTest.kt` L128-140 |
| 安装路径（桌面 `~/.cpplayer/` / Android filesDir + `/lyrics-plugins`） | `LyricoLyricsPluginService.kt` L236-239；`core/src/jvmMain/kotlin/cp/player/core/util/DesktopDataDir.kt` L25-57；`core/src/androidMain/kotlin/cp/player/core/util/PlatformInfo.kt` L22-25；`core/src/desktopMain/kotlin/cp/player/core/util/PlatformInfo.kt` L14-15 |
| 设置页：导入 zip、排序、开关双写、删除二次确认 | `app/src/commonMain/kotlin/cp/player/app/ui/screen/LyricsPluginSettingsScreen.kt` L86-189；`app/src/commonMain/kotlin/cp/player/app/ui/screen/SettingsRegistry.kt` L517-539 |
| 配置表单 UI 未实现（app 侧无 `setPluginConfigValue` / `LyricsSourceConfigField` 调用方） | 全仓 grep（app/src 无匹配）；声明在 `LyricsPluginService.kt` L34-37、`LyricsSourceRegistry.kt` L54-77 |
| `searchCovers` 无宿主调用方；`picUrl`/`coverUrl` 不被统一链路消费 | 全仓 grep；`PluginLyricsSource.kt` L51-52；`LyricoLyricsPluginService.kt` L219 |
| `lyrics_source_mode` 仅迁移读取 | `core/src/commonMain/kotlin/cp/player/core/api/AmllTtmlClient.kt` L31；`FileLyricsSourceRegistry.kt` L199-219 |
| 内置示例插件行为（cache/http/sha256/log、rawTtml 返回） | `core/src/jvmMain/resources/lyrics-plugins/lunabeat-ttml-hub/source.js`、`manifest.json`、`../index.txt` |
| mediaId 透传给 searchSongs/getLyrics | `PluginModels.kt` L24、L33、L59；`LyricsPluginTest.kt` L182-216 |
