# 歌词源插件

> Lyrico Plugin API 兼容插件：包结构、manifest、`Platform.*`、脚本契约、回退链。
> 适用读者：歌词源插件作者。宿主契约 Host API v4 / 插件 API 1–5。
> 事实源 `core/src/.../lyricsplugin/`；与音源插件是两套体系，音源见 [providers/MANIFEST](../providers/MANIFEST.md)。

## 1. 概述

插件是一个 JavaScript 脚本包，遵循 [Lyrico Plugin API](https://github.com/Replica0110/Lyrico)（Apache-2.0），由 Mozilla Rhino（JVM JS 引擎，Android 与桌面共用同一实现）执行。

宿主对 Lyrico 契约逐字复刻：`Platform.*` 引导层与全局 `__invoke` 调度器都与上游一致，按 Lyrico Plugin API 写的插件无需修改即可导入。

| 解决的问题 | 内容 |
|---|---|
| 内置来源未命中 | AMLL 官方词库 / 音源自带歌词 / 本地边车与内嵌歌词都没有时，由用户显式启用的插件补位 |
| 无逐字歌词 | 接入 TTML / 逐字 LRC 词库（内置示例接入 LunaBeat TTML 目录） |

### 1.1 与音源插件的区别

| 维度 | 歌词源插件 | 音源插件（Provider） |
|---|---|---|
| manifest 数据类 | `cp.player.core.lyricsplugin.PluginManifest` | `cp.player.core.provider.ModuleManifest` |
| 关键字段 | `versionCode` / `entry` / `capabilities` | `contract` / `type` / `entryPoint` / `sdkOrigins` |
| 执行体 | JS（Rhino，Android + 桌面同一实现） | QuickJS（JS 包）、JNI、C ABI |
| 版本编号 | 插件 `apiVersion` 1–5，宿主 `HOST_API_VERSION = 4` | 契约版本 `kmp-sdk-v1`（字符串） |
| 管理界面 | 设置 → 账号与音源 → 歌词来源 | 设置 → 账号与音源 → 音源 |

两套版本编号互不相干。`ModuleManifest` 的 KDoc 已写明这一点；实现里歌词侧实际是 `PluginManifest.apiVersion` 与 `minHostApiVersion`，以代码为准。不要拿音源的契约版本套歌词 manifest。

### 1.2 术语

| 术语 | 说明 |
|---|---|
| PluginManifest | 插件目录下的 `manifest.json`，Lyrico 兼容 schema |
| Platform | JS 侧宿主 API 命名空间（`Platform.http.getText(...)`） |
| `__invoke` | 宿主调用插件入口的全局调度器；插件只需暴露 `globalThis.searchSongs` / `globalThis.getLyrics` |
| LyricsSourceRegistry | 统一来源注册表：内置来源与插件同列一张可排序表，顺序即优先级 |
| capabilities | manifest 能力声明；未声明 `getLyrics` 的插件永远不会被取词链路调用 |

## 2. 包结构与安装

### 2.1 ZIP 布局

宿主递归扫描 zip 内所有 `manifest.json`（任意深度），每个含 manifest 的目录视为一个插件，一个 zip 可打包多个插件。

```text
my-lyrics-plugin.zip
└── my-lyrics-plugin/          # 目录名任意，最终以 manifest.id 为准落盘
    ├── manifest.json          # 必需
    ├── source.js              # manifest.entry 指向的入口脚本（缺省即 source.js）
    └── lib/                   # 可选：manifest.includeDirs 声明的目录
        └── util.js            #   该目录下所有 *.js 按路径序拼在入口脚本之前
```

| 限制 | 值 |
|---|---|
| zip 条目数 | ≤ 512 |
| 单条目解压后 | ≤ 16 MiB |
| 整包解压后 | ≤ 64 MiB |
| 路径穿越（zip-slip） | 拒绝 |

超出任一限制即导入失败。

### 2.2 安装路径

| 平台 | 插件目录 | 状态文件 |
|---|---|---|
| 桌面（Windows / Linux / macOS） | `~/.cpplayer/lyrics-plugins/<id>/` | `~/.cpplayer/lyrics-plugins/state.json` |
| Android | `<应用私有目录 files>/lyrics-plugins/<id>/` | 同目录 `state.json` |

`<id>` 是 manifest 的 `id` 经字符清洗后的目录名（`[^A-Za-z0-9._-]` 一律替换为 `_`）。`state.json` 存启用状态与各插件自定义配置；重新导入同 id 插件会先整目录覆盖。

### 2.3 导入

入口：**设置 → 账号与音源 → 歌词来源 → 导入歌词源插件（zip）**。

| 项 | 内容 |
|---|---|
| 桌面 | 系统文件对话框，仅过滤 `.zip` |
| Android | SAF 选 zip |
| 导入后 | 自动重扫来源列表；新插件默认停用，需用户手动打开 |

### 2.4 内置插件

随应用分发一个内置示例 `lunabeat-ttml-hub`（LunaBeat 开放 TTML 歌词目录，源码在 `core/src/jvmMain/resources/lyrics-plugins/`）。

| 规则 | 内容 |
|---|---|
| 载入 | 从 classpath 资源 `lyrics-plugins/index.txt` 列出的目录载入，不可删除 |
| 去重 | 同 id 的导入插件不能覆盖内置插件（载入时内置优先） |
| includeDirs | 不能运行时列目录，需在打包时把文件清单展开成 `<dir>/index.txt`；导入型插件无此限制（宿主直接遍历目录） |
| 默认状态 | 停用；启用才代表用户同意访问其数据源 |

## 3. manifest.json

数据类 `PluginManifest`（kotlinx.serialization，`ignoreUnknownKeys = true`，未知字段被忽略）。

```json
{
  "id": "my-lyrics-plugin",
  "name": "My Lyrics",
  "entry": "source.js",
  "capabilities": ["searchSongs", "getLyrics"]
}
```

| 字段 | 类型 | 必填 | 缺省 | 语义 |
|---|---|---|---|---|
| `id` | String | 是 | — | 全局唯一；落盘目录名、缓存命名空间、启用状态都按它索引 |
| `name` | String | 是 | — | 展示名（来源列表、播放页歌词来源标签） |
| `versionCode` | Int | 否 | `0` | 整数版本号 |
| `versionName` | String | 否 | `""` | 展示版本；为空时管理页显示 `versionCode` |
| `author` | String | 否 | `""` | 作者，管理页副标题 |
| `description` | String | 否 | `""` | 描述 |
| `apiVersion` | Int | 否 | `1` | 插件实现的插件 API 版本，接受 1–5；越界在载入时拒收（不进列表、不报错弹窗） |
| `minHostApiVersion` | Int | 否 | `1` | 要求的宿主最低 API 版本，接受 1–4（当前 `HOST_API_VERSION = 4`）；越界同样拒载 |
| `entry` | String | 否 | `"source.js"` | 入口脚本相对路径；为空或文件不存在即拒载 |
| `includeDirs` | Array\<String\> | 否 | `[]` | 附加脚本目录；其中全部 `*.js` 按路径序拼在入口之前求值。脚本内 `include(path)` 只校验「已声明」，不做事 |
| `icon` | String? | 否 | `null` | 已解析，当前宿主未使用 |
| `capabilities` | Array\<String\> | 否 | `[]` | 缺省按「仅 `searchSongs`」处理，这样的插件永远不会被取词 |
| `configFields` | Array\<Object\> | 否 | `[]` | 自定义配置项声明；宿主已解析并透传，但 UI 尚未渲染表单，也没有写入入口，请求里的 `config` 恒为空对象 |

### 3.1 capabilities

| 值 | 语义 | 宿主行为 |
|---|---|---|
| `searchSongs` | 实现 `globalThis.searchSongs` | 声明 `getLyrics` 时必需配套：取词链路先搜歌再取词 |
| `getLyrics` | 实现 `globalThis.getLyrics` | 取词闸门：未声明则宿主从不调用（`fetchLyricsFrom` 直接返回未命中） |
| `searchCovers` | 实现 `globalThis.searchCovers` | 当前无任何宿主代码调用，只在管理页显示「搜索封面」标签 |

### 3.2 configFields

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
| `options` | Array\<Object\> | `[]` | `dropdown` 候选：`{value, label, summary=""}` |

配置表单 UI 尚未实现，没有任何界面会写入这些配置。插件应能在 `config` 为空时工作（内置示例就不依赖任何配置）。

## 4. 宿主 API

### 4.1 调用约定

宿主在求值插件脚本前注入引导层，JS 侧直接用高层封装：

```js
var text = Platform.http.getText('https://example.com/api');
```

链路：`Platform.* → __lyricoHostCall(name, payloadJson) → 宿主返回 {"value": ...} → 取 .value`。

| 规则 | 内容 |
|---|---|
| 无异常通道 | 失败一律编码进返回值（空串 / null / 空数组） |
| 未注册的 API | 返回 `null` 并记一条告警日志，不抛异常；插件要自己判空，否则连锁出类型错误 |
| 未实现的 Lyrico 部分 | `xml.*` 与 AES 系列 `crypto.aes*` |

### 4.2 app / runtime / i18n

| JS 签名 | 返回 | 说明 |
|---|---|---|
| `Platform.app.getInfo()` | `{name: "CPPlayer", version: "1.0.0"}` | 当前实现为固定值 |
| `Platform.app.getUserAgent()` | `"CPPlayer/1.0"` | 字符串 |
| `Platform.runtime.getInfo()` | `{host: "CPPlayer", hostApiVersion: 4}` | 取自宿主常量 |
| `Platform.i18n.getLocale()` | `"zh-CN"` | 硬编码，不随系统 / 应用语言变化 |
| `Platform.i18n.t(key, ...args)` | `key` 原样返回 | 无翻译表的桩实现，不要用来做界面文案 |

`app` / `runtime` 也分别以 `globalThis.app` / `globalThis.runtime` 暴露（同一对象）。

### 4.3 http

| JS 签名 | 返回 | 失败时 |
|---|---|---|
| `Platform.http.getText(url, options?)` | String（响应体文本） | `""` |
| `Platform.http.postText(url, body, options?)` | String | `""` |
| `Platform.http.get(url, options?)` | 响应体 `JSON.parse` 后的值 | `null`（含「响应不是 JSON」） |
| `Platform.http.post(url, body, options?)` | 同上 | `null` |
| `Platform.http.getBytes(url, options?)` | `number[]` | `[]` |

`options`（全部可选）：

| 字段 | 类型 | 缺省 | 说明 |
|---|---|---|---|
| `headers` | Object | `{}` | 在默认头之后设置，同名头会覆盖默认值 |
| `contentType` | String | `"application/json; charset=utf-8"` | 仅 POST 生效 |
| `connectTimeoutMs` | Number | `8000` | 连接超时 |
| `readTimeoutMs` | Number | `10000` | 读超时 |
| `followRedirects` | Boolean | `true` | 跟随重定向 |

| 行为 | 内容 |
|---|---|
| scheme | 只允许 `http://` / `https://`；`file://` 等直接返回失败（防止插件读本地文件） |
| 默认 UA | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120.0.0.0 Safari/537.36 CPPlayer/1.0`；裸 Java UA 会被各大音乐服务 403 |
| 其它默认头 | `Accept: */*`、`Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`、`Accept-Encoding: gzip, deflate` |
| 重定向 | 301/302/303/307/308 由宿主手工跟随，最多 3 跳，支持 http↔https；303 改 GET |
| 压缩 | `gzip` / `deflate` 自动解压 |
| 状态码 | 不暴露给 JS：非 2xx 也照样返回响应体；判错误要靠内容（解析失败，或像内置示例先校验 `schemaVersion`） |
| 异常 | 任意网络异常（DNS、超时、TLS）都被吞掉，按上表返回失败值 |

### 4.4 cache

| JS 签名 | 返回 | 说明 |
|---|---|---|
| `Platform.cache.get(key)` | String，未命中 `""` | 先查内存表，再查磁盘 |
| `Platform.cache.set(key, value, ttlMs?)` | null | `ttlMs` 当前被忽略，写入即永不过期（直至 remove / clear） |
| `Platform.cache.remove(key)` | null | — |
| `Platform.cache.clear()` | null | 清空本插件的全部缓存 |

| 命名空间 | 内容 |
|---|---|
| 内存 | 按插件实例隔离（每个插件一个宿主对象） |
| 磁盘 | 文件名 `<pluginId>-<sha256(key) 前 32 位>`，落在插件缓存根目录下 |
| 当前装配 | 未向插件服务传磁盘缓存目录，因此桌面 / Android 实际只有进程内内存缓存，应用重启即失 |

依赖缓存跨会话的场景要把「冷启动无缓存」当常态设计。

### 4.5 crypto / base64 / bytes / compression

| JS 签名 | 返回 |
|---|---|
| `Platform.crypto.md5(text)` | 十六进制摘要 String |
| `Platform.crypto.sha256(text)` | 十六进制摘要 String |
| `Platform.base64.encodeText(text)` / `decodeText(base64)` | String |
| `Platform.base64.encodeBytes(number[])` | base64 String |
| `Platform.base64.decodeBytes(base64)` | `number[]` |
| `Platform.base64.encodeUrlText(text)` / `decodeUrlText(base64Url)` | URL-safe base64 |
| `Platform.base64.encodeUrlBytes(number[])` / `decodeUrlBytes(base64Url)` | URL-safe base64 |
| `Platform.bytes.xor(number[], key[])` | `number[]`；key 循环使用，key 为空原样返回 |
| `Platform.bytes.xorBase64(base64, key[])` | 先解 base64 再 XOR，返回 `number[]` |
| `Platform.compression.inflateBytesToText(number[])` | String（raw zlib inflate，不是 gzip） |
| `Platform.compression.inflateBase64ToText(base64)` | 同上 |

所有解码失败返回空串 / 空数组，不抛异常。

### 4.6 log

| JS 签名 | 说明 |
|---|---|
| `Platform.log.debug(tag?, message)` | 单参数时 tag 取 `"PlatformPlugin"` |
| `Platform.log.warn(tag?, message)` | 同上 |
| `Platform.log.error(tag?, message)` | 同上 |

当前生产装配没有接日志出口：宿主创建插件宿主对象时用默认空 sink，插件日志（含「Unsupported host call」告警）在桌面与 Android 都不会出现在任何日志里。排错手段见第 8 节。

## 5. 脚本契约

### 5.1 入口与求值模型

脚本在一个常驻 Rhino 运行时里求值一次，宿主通过全局调度器调入口。

```js
// 宿主注入的调度器（逐字）：
globalThis.__invoke = function(name, requestJson) {
  var fn = globalThis[name];
  if (typeof fn !== "function") return "null";
  var result = fn(JSON.parse(requestJson || "{}"));
  return JSON.stringify(result === undefined ? null : result);
};
```

插件只需在全局挂函数：

```js
globalThis.searchSongs = function (request) { /* ... */ return [...]; };
globalThis.getLyrics = function (request) { /* ... */ return {...}; };
```

| 规则 | 内容 |
|---|---|
| 返回值 | 宿主 `JSON.stringify` 后再解析；返回 `undefined` 视为 null（未命中） |
| 入口不存在 | 同样按未命中处理，不报错 |
| JS 能力 | ES6（Rhino `VERSION_ES6`，解释执行）；不要依赖 Node API、`fetch`、浏览器 DOM |

### 5.2 searchSongs

宿主发出的请求（字段名与 Lyrico 一致）：

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

| 字段 | 内容 |
|---|---|
| `keyword` | `标题 + 空格 + 歌手`（空白项省略） |
| `page` / `pageSize` | 当前写死 1 / 10；插件仍应按请求值分页，勿硬编码 |
| `config` | 插件自定义配置，当前恒为 `{}` |
| `mediaId` | 当前曲目统一标识 `{providerId}://{type}/{id}`，可能为 null |

宿主先用清洗后的关键词搜（`MusicSearchSanitizer`：去文件扩展名、去音轨序号前缀、去 `[FLAC]` / `(Live)` 类标记、多歌手取第一个、尝试拆分「歌手 - 歌名」），无命中且原词不同则用原词再搜一次。

返回：JSON 数组，或 `{ "items" | "results" | "songs" | "data": [...] }`。字段宽容解析（同义名任选其一；解析不出的条目被跳过，不抛异常）：

| 目标字段 | 接受的键名 | 说明 |
|---|---|---|
| `id`（必填） | `id` / `songId` / `trackId` | 没有 id 的条目整条丢弃 |
| `title` | `title` / `name` / `songName` | — |
| `artist` | `artist` / `artists` / `singer` | 数组会被 `/` 连接 |
| `album` | `album` / `albumName` | — |
| `duration` | `duration` / `durationMs` / `duration_ms` | 毫秒；打分器用它比对时长 |
| `picUrl` | `picUrl` / `coverUrl` / `cover_url` / `artworkUrl` | 已解析，但统一来源链路当前不消费封面 |
| `date` | `year` / `date` / `releaseDate` / `release_date` | — |
| `trackNumber` | `trackNumber` / `trackerNumber` / `track_number` | — |
| `fields` | `fields` / `metadata` | 附属元数据，透传回 `getLyrics` 的 `song.fields` |
| `internal` | `internal` | 插件私有透传，原样回传给 `song.internal`；限 ≤64 键、键 ≤64 字符、值 ≤4096 字符，超限被裁剪 |

宿主选歌不由插件决定：`LyricsMatchScorer` 按歌名 / 歌手 / 专辑 / 时长综合打分（含伴奏、翻唱、Live 等负向惩罚），选最高置信度一条；全部不达标视为未命中，交给下一条来源。

| 插件应做 | 原因 |
|---|---|
| 返回尽量多的合理候选 | 宿主替你挑 |
| 填准 `duration` | 最重要的消歧信号之一 |

### 5.3 getLyrics

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

四种可接受返回形状：

| 形状 | 含义 |
|---|---|
| `"[00:01.00]..."`（纯字符串） | 按 plain LRC 解析 |
| `null` / `{ "notFound": true }` | 未命中，下一条来源继续 |
| `[ {...}, {...} ]` | API 4 多候选：宿主取第一个可解析出内容的项 |
| `{ "type": "...", ... }` | 主路径 |

对象形态的 `type`（大小写不敏感）与负载字段：

| `type` | 别名 | 负载字段（别名） | 宿主处理 |
|---|---|---|---|
| `structured`（缺省） | — | `original` / `translated` / `romanization` | 结构化行直接映射 |
| `rawPlainLrc` | `raw_plain_lrc` / `plainLrc` / `plain_lrc` / `lrc` | `rawPlainLrc`（+ `originalLrc` / `original_lrc` / `lrc`） | 行级 LRC 解析器 |
| `rawVerbatimLrc` | `raw_verbatim_lrc` | `rawVerbatimLrc` / `raw_verbatim_lrc` | 逐字 LRC 解析器（保留词级时间戳） |
| `rawEnhancedLrc` | `raw_enhanced_lrc` | `rawEnhancedLrc` / `raw_enhanced_lrc` | 同上 |
| `rawTtml` | `raw_ttml` / `ttml` | `rawTtml` / `raw_ttml` | TTML 解析器 |
| `rawMultiPersonEnhancedLrc` | `raw_multi_person_enhanced_lrc` | `rawMultiPersonEnhancedLrc` / `raw_multi_person_enhanced_lrc` | 逐字 LRC 解析器 |

通用可选字段：`tags`（对象，如 `{ti, ar, al}`，原样保留）。

structured 行格式（时间单位毫秒）：

```json
{
  "type": "structured",
  "original":      [[1000, 2000, [[1000, 1500, "你"], [1500, 2000, "好"]]]],
  "translated":    [[1000, 2000, "Hello"]],
  "romanization":  [[1000, 2000, [[1000, 2000, "ni hao"]]]]
}
```

| 规则 | 内容 |
|---|---|
| 行结构 | `[startMs, endMs, words]` |
| `words` | 逐字数组 `[[wordStart, wordEnd, text], ...]`，或整行字符串；`original` / `romanization` 两种形态都收，`translated` 只收字符串 |
| `endMs` | 缺省等于 `startMs` |
| 未命中判定 | `original` 解析不出任何行；或最终行数为 0 |
| 翻译 / 罗马音配对 | 按时间就近 ±1500ms 与原文行配对，配不上的丢弃 |
| 空行 | 行文本为空的行被丢弃 |

### 5.4 能力与调用

| manifest `capabilities` | 宿主行为 |
|---|---|
| 含 `getLyrics` | 正常参与取词（前提：同时实现并能被 `searchSongs` 找到候选） |
| 只含 `searchSongs`（或缺省） | 永不参与取词，仅在管理页可见 |
| 含 `searchCovers` | 只显示能力标签，暂无调用方 |

## 6. 生命周期与隔离

### 6.1 运行时

| 项 | 内容 |
|---|---|
| 创建 | 每插件一个常驻 Rhino 运行时，首次取词时惰性创建（创建即求值整份脚本：include 拼在前、入口在后），之后复用 |
| 销毁 | 删除插件即关闭并销毁其运行时；应用退出时全部随服务关闭 |
| 热替换 | 重新导入同 id 插件不会热替换运行中的旧脚本（运行时缓存按 id 驻留）；管理页删除再导入可强制刷新 |

### 6.2 线程

| 项 | 内容 |
|---|---|
| 单线程 | Rhino 运行时非线程安全，所有求值 / 调用收敛到一个专属线程（线程名 `cpplayer-lyrics-plugin`，栈 4 MiB） |
| 串行 | 多个插件也串行执行；脚本里无需考虑并发，也别指望并行提速 |
| 解释执行 | `optimizationLevel = -1`，兼容 Android，无字节码生成 |

### 6.3 超时

| 层 | 限制 |
|---|---|
| JS 执行 | 没有硬性执行超时 / 指令数上限；死循环会占住插件线程并拖住整条取词链（宿主对象里有预留取消标记，当前无人置位）。作者必须让所有等待走 `Platform.http` 并设置合理的 `connectTimeoutMs` / `readTimeoutMs` |
| HTTP | 连接 8s / 读 10s；重定向最多 3 跳 |

### 6.4 异常隔离

| 情形 | 处理 |
|---|---|
| 入口抛异常、返回非法 JSON、求值失败 | 一律吞掉并视为未命中，不影响播放，也不影响其它来源（服务层一层、歌词引擎对每个来源再包一层） |
| 协程取消 | `CancellationException` 不被吞，正常向上传播 |
| 解析 | 宽容解析：脏条目跳过、缺字段给缺省、绝不抛异常 |

### 6.5 存储

| 数据 | 位置 |
|---|---|
| 启用状态 + 插件配置 | `<dataDir>/lyrics-plugins/state.json`（`enabledIds` / `configs[id][key]`） |
| 来源排序 + 停用表 | `<dataDir>/lyrics-sources/sources.json`（`order` / `disabled` / `configs`） |
| 插件脚本缓存 | 见 4.4，当前生产仅内存 |

启用状态有两份记录：`sources.json` 的 `disabled` 决定调度（统一来源体系只取未停用的来源），`state.json` 的 `enabledIds` 服务于旧链路。设置页开关同时写两处，正常不会分叉。

## 7. 完整示例

以内置 `lunabeat-ttml-hub` 为蓝本（演示 `http.getText` / `cache.*` / `crypto.sha256` / `log.warn` 与 `rawTtml` 返回）。假设静态歌词站 `https://lyrics.example.com`，搜索 `GET /search?q=` 返回歌曲数组，歌词 `GET /lyric/<id>` 返回 LRC 文本。

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

`apiVersion` 填 1–5 均可；`minHostApiVersion` 填 1–4。

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

| 要点 | 内容 |
|---|---|
| 入口位置 | 必须挂在 `globalThis` 上（宿主按名字找全局函数） |
| 未命中 | 返回 `null` 或 `{notFound: true}`，不要用抛异常表达未命中 |
| `internal` | 搜歌 → 取词之间的私有透传通道（大小受限），别塞大对象 |
| 逐字效果 | 把 `type` 换成 `rawTtml` / `rawEnhancedLrc` 返回对应原文，或用 `structured` 直接给词级时间戳 |

### 7.3 打包与安装

```text
example-lrc-hub/
├── manifest.json
└── source.js
```

```bash
# 在 example-lrc-hub 的父目录执行（manifest 在 zip 里任意深度都能被找到）
zip -r example-lrc-hub.zip example-lrc-hub/
```

安装：设置 → 账号与音源 → 歌词来源 → 导入歌词源插件（zip）。导入后它出现在来源列表末尾且处于停用状态：打开开关，按需用 ↑/↓ 调位置。

### 7.4 验证被选中

播放一首只有你的词库有的歌，播放页歌词信息会显示来源为 manifest 的 `name`，格式标签为 `Plugin`（行级）或 `Plugin (Karaoke)`（含逐字时间戳）。

## 8. 调试与排错

### 8.1 日志

| 通道 | 现状 |
|---|---|
| `Platform.log.*` | 宿主未接日志 sink，插件日志被丢弃（桌面 / Android 皆然） |
| 「Unsupported host call」告警 | 同上 |
| 播放页歌词来源标签 | 最可靠的在线信号：来源名 = 插件 `name`，格式 = `Plugin` / `Plugin (Karaoke)` |

实用做法：把脚本核心逻辑抽成纯函数，本地用任意 JS 引擎 + mock 的 `Platform` 跑单测；或参考 `core/src/desktopTest/.../LyricsPluginTest.kt` 的模式，直接用 `RhinoPluginRuntime(JvmPluginHostApi(...))` 在 JVM 上复现宿主契约 —— 这是当前唯一能看到插件日志与精确返回值的方式。

### 8.2 常见失败

| 症状 | 可能原因 | 判据 / 解法 |
|---|---|---|
| 导入即失败（「文件不是有效的插件包」） | zip 里没有 `manifest.json`；JSON 不合法（注释 / 尾逗号 / 单引号）；`apiVersion` / `minHostApiVersion` 越界；`entry` 缺失或文件不存在；超出大小限制 | 先过 JSON 校验器；核对版本区间 1–5 / 1–4；`apiVersion: 99` 这类越界连列表都进不去，且是静默的 |
| 导入成功但取词从不调用 | `capabilities` 未声明 `getLyrics` | 加 `"capabilities": ["searchSongs", "getLyrics"]` |
| 插件没被执行 | 开关没打开；排序在前的来源已命中 | 管理页确认开关与顺序（首个命中即胜出） |
| 搜索有结果但整体未命中 | 候选缺 `id`（整条丢弃）；打分器全不达标；`internal` 超限被裁剪 | 填准 `id` 与毫秒 `duration` |
| `getLyrics` 返回了但显示无歌词 | 负载字段名不在别名表；structured 的 `original` 为空或行文本全空；raw 文本为空 | 对照 5.3 的表核字段名 |
| HTTP 全是空串 | URL 非 http / https；目标 403 / 超时；跨协议重定向超过 3 跳 | 检查 URL；必要时显式 `headers` 覆盖 UA；超时默认 8s / 10s 可调 |
| 改了 zip 重新导入但行为没变 | 运行时按 id 驻留，旧脚本未释放 | 管理页删除该插件再导入 |
| 一切正常但 `config` 是空的 | 配置表单 UI 尚未实现 | 插件设计为无配置可用 |

### 8.3 来源排序

- 管理页列表自上而下就是取词顺序，关闭的来源直接跳过。
- 新导入的插件自动追加在列表末尾，不会插到内置来源之前。
- 想确认插件被问到：拖到最顶，播放一首已知的歌，来源标签应变为插件名。

## 9. 取词回退链

生产取词走统一来源体系：`LyricsEngine` 按用户排定的顺序逐个询问已启用且声明了取词能力的来源，首个命中即胜出。

默认顺序（首次安装 / 旧配置迁移时写入）：

```text
本地边车歌词 → 音频内嵌歌词 → AMLL 官方词库 → 音源自带歌词 → [插件…追加在后]
```

三条不变量：

| 不变量 | 内容 |
|---|---|
| 默认停用 | 插件全部停用（含内置 `lunabeat-ttml-hub`）；未启用的不参与调度，不产生任何网络请求 —— 启用动作本身就是用户对访问该数据源的授权 |
| 串行 | 插件串行参与回退链，不做并发竞速：Rhino 运行时非线程安全，竞速还会让同一首歌两次显示不同来源的词 |
| 不越权 | 插件永远只是来源之一，不会盖过用户排在前面的 AMLL / 音源 / 本地歌词 |

历史路径备查：未装配注册表的最小环境（主要是测试）里存在一条旧回退链（AMLL → 音源 API → 插件兜底），插件固定位于 AMLL 与音源 API 之后。应用本体总是装配注册表，正常用户走不到。

## 10. 持久化与设置键

| 内容 | 位置 / 键 | 说明 |
|---|---|---|
| 来源顺序 + 停用表 + 来源配置 | `<dataDir>/lyrics-sources/sources.json`（`version=3` / `order` / `disabled` / `configs`） | 统一来源体系的唯一事实源 |
| 插件启用状态 + 自定义配置 | `<dataDir>/lyrics-plugins/state.json`（`enabledIds` / `configs`） | 插件服务自管 |
| 插件文件 | `<dataDir>/lyrics-plugins/<id>/` | 导入型插件；内置的在 classpath |
| 旧三档来源设置 | 设置键 `lyrics_source_mode` | 仅一次性迁移读取（`sources.json` 缺失时换算成新顺序），之后不写也不删 |
| 桌面 `<dataDir>` | `~/.cpplayer/` | — |
| Android `<dataDir>` | 应用私有 `files` 目录 | — |

## 11. 兼容性

| 契约点 | 值 |
|---|---|
| 插件 API 版本（manifest `apiVersion`） | 接受 1–5 |
| 宿主 API 版本（`runtime.getInfo().hostApiVersion`） | 4 |
| 要求的宿主版本（manifest `minHostApiVersion`） | 接受 1–4 |
| JS 语法 | ES6（Rhino 解释执行） |
| 歌词格式 | TTML / plain LRC / 增强（逐字）LRC / 多人增强 LRC / structured |
| 时间单位 | 毫秒（`duration`、`start` / `end` 一律毫秒） |
| 与 Lyrico 的差异 | 未实现 `xml.*` 与 AES 系列；i18n 是桩实现；cache TTL 被忽略；日志无出口 |

## 12. 事实来源

| 结论 | 代码锚点 |
|---|---|
| manifest 全字段与缺省 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/PluginManifest.kt` L21-36 |
| capabilities 三值；缺省按仅 searchSongs | 同上 L38-49；`LyricoLyricsPluginService.kt` L61、L108、L137-138 |
| `configFields` 子结构与类型枚举 | `PluginManifest.kt` L51-98 |
| 版本常量 1–5 / `HOST_API_VERSION=4` | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/HostApiRegistry.kt` L16-25 |
| 已实现的 Platform 调用名集合；xml / AES 未实现 | 同上 L28-66 |
| 两套独立版本编号的 KDoc；`HOST_PROVIDER_API_VERSION = 3` | `core/src/commonMain/kotlin/cp/player/core/provider/ModuleManifest.kt` L89-104 |
| JS 引导层全部 `Platform.*` 签名 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/RhinoPluginRuntime.kt` L127-283 |
| `__invoke` 调度器与入口约定 | 同上 L55-64、L115-124 |
| Rhino 解释模式 / ES6 / 非线程安全 | 同上 L40-41、L71-83 |
| `{"value": ...}` 返回包装 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/JvmPluginHostApi.kt` L33-38、L165 |
| i18n 桩（zh-CN / 原样返回 key） | 同上 L70-71 |
| app.info / userAgent / runtime.info | 同上 L61-69 |
| cache TTL 被忽略；命名空间 `<pluginId>-<sha256前32位>` | 同上 L261-263、L282-283 |
| http 行为（scheme 白名单 / 默认头 / 超时 / 重定向 ≤3 / gzip / 状态码不暴露 / 失败吞掉） | 同上 L172-237、L317-319 |
| 未注册 API → null + 告警 | 同上 L160-163 |
| log sink 默认空实现；生产装配未传 sink / cacheDir | 同上 L44；`LyricoLyricsPluginService.kt` L41-44；`core/src/commonMain/kotlin/cp/player/core/MusicBackend.kt` L434-436 |
| 预留取消标记未使用 | `JvmPluginHostApi.kt` L50-56 |
| 单线程调度器（线程名 / 4 MiB 栈）、每插件常驻运行时 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/LyricoLyricsPluginService.kt` L31-46 |
| 关键词清洗 + 二次原词重试；page=1 / pageSize=10 | 同上 L156-179；`core/src/commonMain/kotlin/cp/player/core/util/MusicSearchSanitizer.kt` L10-98 |
| 打分器选候选 | `LyricoLyricsPluginService.kt` L182-199 |
| 请求 / 响应模型 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/PluginModels.kt` L17-76 |
| 宽容解析：别名表、internal 限长、多候选取第一、±1.5s 配对、行格式 | `core/src/commonMain/kotlin/cp/player/core/lyricsplugin/LyricsPluginJsonParser.kt` L31-131、L192-233 |
| payload type 与别名、raw 交给宿主解析器 | 同上 L140-157、L263-272 |
| 异常隔离（服务层 + 引擎层） | `LyricoLyricsPluginService.kt` L110-118、L139-143；`core/src/commonMain/kotlin/cp/player/core/lyrics/LyricsEngine.kt` L32-42 |
| 取词主链路（引擎 + resolveToState + 格式标签） | `LyricsEngine.kt` L23-69；`core/src/commonMain/kotlin/cp/player/core/playback/PlaybackControllerImpl.kt` L1361-1378 |
| 旧回退链；未启用零网络请求 | `PlaybackControllerImpl.kt` L1388-1470；`MusicBackend.kt` L349-350 |
| 注册表：sources.json、默认顺序、迁移、追加在后 | `core/src/jvmMain/kotlin/cp/player/core/lyrics/FileLyricsSourceRegistry.kt` L44-56、L144-155、L186-219；`core/src/commonMain/kotlin/cp/player/core/lyrics/LyricsSource.kt` L116-126 |
| 插件适配为 LyricsSource（mediaId 透传） | `core/src/jvmMain/kotlin/cp/player/core/lyrics/PluginLyricsSource.kt` L24-54 |
| zip 导入：递归找 manifest、多插件、大小限制、内置优先 | `core/src/jvmMain/kotlin/cp/player/core/lyricsplugin/FileLyricsPluginStore.kt` L55-144、L227-259、L271-275 |
| manifest 校验 | 同上 L216-225 |
| state.json | 同上 L34-51、L160-175 |
| 内置插件：index.txt、不可删、include 需打包清单、默认停用 | 同上 L26-27、L66-98、L146-156；`core/src/desktopTest/kotlin/cp/player/core/lyricsplugin/LyricsPluginTest.kt` L128-140 |
| 安装路径 | `LyricoLyricsPluginService.kt` L236-239；`core/src/jvmMain/.../util/DesktopDataDir.kt` L25-57；`core/src/androidMain/.../util/PlatformInfo.kt` L22-25；`core/src/desktopMain/.../util/PlatformInfo.kt` L14-15 |
| 设置页：导入 zip、排序、开关双写、删除二次确认 | `app/src/commonMain/kotlin/cp/player/app/ui/screen/LyricsPluginSettingsScreen.kt` L86-189；`SettingsRegistry.kt` L517-539 |
| 配置表单 UI 未实现 | 全仓 grep（`app/src` 无匹配）；声明在 `LyricsPluginService.kt` L34-37、`LyricsSourceRegistry.kt` L54-77 |
| `searchCovers` 无调用方；`picUrl` / `coverUrl` 不被消费 | 全仓 grep；`PluginLyricsSource.kt` L51-52；`LyricoLyricsPluginService.kt` L219 |
| `lyrics_source_mode` 仅迁移读取 | `AmllTtmlClient.kt` L31；`FileLyricsSourceRegistry.kt` L199-219 |
| 内置示例插件行为 | `core/src/jvmMain/resources/lyrics-plugins/lunabeat-ttml-hub/` 的 `source.js`、`manifest.json`、`../index.txt` |
| mediaId 透传 | `PluginModels.kt` L24、L33、L59；`LyricsPluginTest.kt` L182-216 |

## 相关

- 音源插件：[providers/index](../providers/index.md)
- 用户侧歌词设置：[USER_GUIDE 第 3 节](../../USER_GUIDE.md#3-功能与入口)
- 架构：[ARCHITECTURE](../ARCHITECTURE.md)
