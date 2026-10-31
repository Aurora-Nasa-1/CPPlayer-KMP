# CPPlayer 现代音源开发与第三方平台移植开发指南 (Provider Standard v3)

> **适用场景：** 为 CPPlayer 开发全新的轻量音乐源，或将 QQ 音乐、酷狗、酷我、Bilibili、Subsonic/Navidrome、YouTube Music、本地/自建源移植到 CPPlayer。  
> **数据源标准：** 本文档内容**完全直接源自 CPPlayer 宿主核心源码**（`UnifiedMusicSourceImpl.kt`, `MusicSourceFromApi.kt`, `LyricsParser.kt`, `ModuleManifest.kt`, `HttpProvider.kt`, `PlatformSupport.kt` 等）。  
> *注：如果你要开发的是针对网易云历史二进制私有加密的逆向接口包，请查阅 [网易云 Legacy 完整接口参考手册](PROVIDER_DEV_GUIDE.md)。*

---

## 1. 核心架构设计理念

CPPlayer Standard v3 协议规范的核心理念是 **“开发者减负、能力渐进式扩展、UI 智能自适应”**：

1. **最简核心契约（极简起步）：**  
   起步必须实现 **3 个基础 API**：搜歌 `POST /search`、歌曲详情 `POST /song/detail`、取流直链 `POST /song/url`；歌词 `POST /lyric` 可选。没有歌曲详情时，未预先注入摘要的播放入口、队列恢复和批量预加载无法可靠工作，不能只实现搜索与直链就声称完整可用。
2. **现代扁平数据模型：**  
   告别历史平台繁复的深度嵌套（如 `songs[0].al.picUrl`, `ar[0].name`），直接返回扁平字段：`title`, `artist`, `album`, `coverUrl`, `durationMs`。
3. **声明式能力网格（按声明裁剪 UI，未声明不做限制）：**  
   音源通过 `manifest.json` 的 `capabilities` 声明自己支持的功能，宿主据此决定入口的**可见性**。语义分两种情况（`ModuleManifest.isFeatureAvailable`）：
   - **完全没写 `capabilities`（null）**：历史兼容模式，宿主**不裁剪任何入口**（老包的行为）；
   - **写了 `capabilities` 数组**：未声明的能力在界面层隐藏对应入口。
   
   当前**真正做了 UI 门控**的能力有且只有 3 个：`comments`（评论区入口）、`cloudDrive`（云盘入口）、`recommend`（首页推荐区）。其余能力值目前用于**能力徽标展示**与「为什么某个按钮没有」的说明，不改动入口显隐 —— 见 §4 的逐项说明。**不要**指望声明/不声明某个值就自动获得一整套页面。
4. **跨语言运行时支持（HTTP / JNI / Binary）：**  
   支持 Node.js/Python/Go/Java 编写的 HTTP 服务（远程或本地），也支持 Rust/C++ 编译出的轻量原生 JNI 动态库（`.dll` / `.so`），兼顾开发便捷度与极致性能。
5. **免写 UI 代码的 Material 3 设置页：**  
   在 `manifest.json` 中声明 `settingsSchema`，CPPlayer 自动渲染原生表单并自动注入配置，插件不必另存一份配置；登录会话等运行状态仍由插件负责管理。

---

## 2. 音源插件包规范与目录结构

一个标准 CPPlayer 音源插件是一个 `.zip` 压缩包（可重命名后缀为 `.cpm`，即 CPPlayer Module；注意 **桌面端导入对话框只过滤 `.zip`**，`.cpm` 文件在桌面端选不中，Android 端经 SAF 不受后缀限制）。

### 2.1 运行时类型 (`type`)

CPPlayer 支持三种音源运行时类型：

| 类型 (`type`) | 运行方式 | 适用场景 | `entryPoint` 定义 |
|---|---|---|---|
| `"http"` | 远程或本地 HTTP/HTTPS 服务 | Python / Node.js / Go / 云端或内网部署服务 | 如 `"http://127.0.0.1:3000"` 或 `"https://api.example.com"` |
| `"jni"` | 桌面优先由独立 JNI Worker 加载，失败时回退宿主进程；Android 进程内加载 | Rust / C++ / C 高性能跨平台模块 | 库文件名，如 `"libkugou_music_rs.so"` 或 `"kugou_music_rs.dll"` |
| `"binary"` | 宿主自动拉起本地子进程守护执行 | Go / Rust 编译的独立本地单二进制可执行文件。**执行进程仅桌面端可用**；Android 只有在包内找到兼容 `.so` 时才会提升为 JNI，否则明确拒绝 | 可执行文件名，如 `"my_server.exe"`；Android 需另提供对应 JNI 库 |

### 2.2 打包目录布局

#### HTTP 音源包：
```
my-http-provider.zip (或 .cpm)
└── manifest.json
```

#### 原生 JNI 动态库音源包（多平台跨 ABI 支持）：
宿主加载时优先按 `lib/<ABI>/<entryPoint>` 寻址，找不到时回退到根目录 `<entryPoint>`。ABI 目录不区分 OS；下图展示可用的目录形式，**不是一个 entryPoint 就能通吃所有后缀的跨 OS 包**，同 CPU 的 Windows / Linux / macOS 应分别发布：
```
my-jni-provider.zip (或 .cpm)
├── manifest.json
└── lib
    ├── arm64-v8a/libmy_music_rs.so      # Android 64 位
    ├── armeabi-v7a/libmy_music_rs.so    # Android 32 位
    └── x86_64/my_music_rs.dll           # Windows 64 位 (或 .so/.dylib)
```

---

## 3. 插件清单规范 (`manifest.json`)

`manifest.json` 是音源的唯一元数据定义文件，放置于 ZIP 根目录。

### 3.1 完整清单示例

```json
{
  "id": "my-music-source",
  "name": "我的第三方音乐源",
  "version": "1.0.0",
  "type": "http",
  "entryPoint": "http://127.0.0.1:3000",
  "contract": "standard",
  "apiVersionTag": "standard-v3",
  "minHostApiVersion": 1,
  "supportedAbis": [
    "arm64-v8a",
    "x86_64"
  ],
  "capabilities": [
    "search",
    "playback",
    "lyricsSearch",
    "wordSyncedLyrics",
    "userPlaylists",
    "artistExplorer",
    "albumExplorer",
    "recommend",
    "charts",
    "comments",
    "qrLogin",
    "webviewLogin"
  ],
  "loginMethods": [
    "qr",
    "webview",
    "cookie"
  ],
  "apiMap": {
    "cloudsearch": "search",
    "song/url/v1": "song/url",
    "song/url/v1/302": "song/url",
    "song/download/url/v1": "song/url",
    "lyric/new": "lyric",
    "song/detail": "song/detail",
    "search/hot/detail": "search/hot",
    "search/suggest": "search/suggest",
    "toplist": "toplist",
    "toplist_detail": "toplist/detail",
    "artist/detail": "artist/detail",
    "artist/songs": "artist/songs",
    "artist/top/song": "artist/songs",
    "artist/album": "artist/albums",
    "album": "album",
    "user/playlist": "user/playlist",
    "playlist/detail": "playlist/detail",
    "playlist/track/all": "playlist/track/all",
    "recommend/songs": "recommend/songs",
    "comment/new": "comment/new",
    "comment/floor": "comment/floor",
    "login/qr/key": "login/qr/key",
    "login/qr/create": "login/qr/create",
    "login/qr/check": "login/qr/check",
    "login/status": "login/status",
    "login/webview/url": "login/webview/url",
    "login/webview/check": "login/webview/check"
  },
  "settingsSchema": [
    {
      "key": "serverUrl",
      "label": "自建服务器地址",
      "type": "string",
      "placeholder": "https://music.nas.local:4533",
      "required": true
    },
    {
      "key": "quality",
      "label": "默认音质",
      "type": "select",
      "default": "lossless",
      "options": [
        { "label": "标准 (128k)", "value": "standard" },
        { "label": "高品质 (320k)", "value": "exhigh" },
        { "label": "无损 (FLAC)", "value": "lossless" }
      ]
    }
  ]
}
```

### 3.2 顶层字段说明

| 字段 | 类型 | 是否必选 | 说明 |
|---|---|---|---|
| `id` | `String` | **必选** | 音源唯一全局标识，如 `"qqmusic-rs"`, `"bilibili-audio"` |
| `name` | `String` | **必选** | 音源显示名称，呈现在音源管理与切换列表中 |
| `version` | `String` | **必选** | 语义化版本号，如 `"1.0.0"` |
| `type` | `String` | **必选** | 运行时类型：`"http"` / `"jni"` / `"binary"` |
| `entryPoint` | `String` | **必选** | HTTP 基地址（如 `"http://127.0.0.1:3000"`）或动态库文件名 |
| `contract` | `String` | **推荐** | 填 `"standard"` 声明采用现代 Standard v3 规范。⚠️ 当前仅为元数据标记（解析器本就兼容两种响应形状），宿主不据此分支行为 |
| `apiVersion` | `Int` | 可选（缺省 `1`） | 插件自身实现的插件 API 版本（当前宿主支持到 `3`）。缺省=1 表示历史行为 |
| `apiVersionTag` | `String` | 可选（缺省不声明） | **接口代际标签**，自由字符串（如 `"netease-v3"` / `"v123"`）。**纯展示**：不拼请求路径、不切解析分支、不作加载闸门；音源管理页与诊断页原样显示，用来把「同一平台的老接口包 / 新接口包」区分开。空白串等价于未声明。上游版本段要进 URL 请自己写进 `entryPoint`（如 `"http://127.0.0.1:3000/v123"`），不要指望这个字段 |
| `minHostApiVersion` | `Int` | 可选（缺省 `1`） | **加载的唯一闸门**：>宿主当前 API 版本（`3`）时拒绝加载并提示升级 |
| `capabilities` | `Array<String>` | **强烈推荐** | 声明音源支持的能力网格清单；写了数组后未声明项在 UI 上裁剪（含能力徽标） |
| `loginMethods` | `Array<String>` | 可选 | 支持的登录通道：`"qr"`(扫码), `"email"`(邮箱), `"sms"`(短信验证码), `"cookie"`(粘贴 Cookie), `"captchaImage"`(图形验证码), `"webview"`/`"web"`(WebView)。缺省=展示全部常见方式 |
| `supportedAbis` | `Array<String>` | 可选 | 多 ABI 原生库：`["arm64-v8a","armeabi-v7a","x86_64"]`，配合 `lib/<abi>/` 目录 |
| `targetAppPackage` | `String` | 可选 | 目标 App Android 包名（登录页「打开目标 App」按钮跳转用，如 `"com.netease.cloudmusic"`） |
| `updateUrl` | `String` | 可选 | 检查更新 URL，指向返回最新版本信息的 JSON 端点 |
| `sha256` | `String` | 已废弃 | 宿主不校验包内 manifest 对自身 ZIP 的哈希，修改 manifest 会改变 ZIP，无法自校验。整包完整性应由外部仓库发布元数据或发布渠道校验 |
| `apiMap` | `Map<String, String>` | 可选 | 端点重映射表：**key=宿主内部标准方法名**（如 `cloudsearch`、`lyric/new`），**value=你的端点**。未映射的方法按宿主方法名原样请求 |
| `settingsSchema` | `Array<Object>` | 可选 | 声明式设置表单定义（见 §9） |

> **`apiMap` 映射方向务必留意**：是 `宿主内部方法名 → 你的端点`（`ProviderManager.callApi` 先查表再直传 value）。key 写不存在的宿主方法名=死键（映射永远不命中）。完整宿主方法名清单见 [PROVIDER_DEV_GUIDE.md](PROVIDER_DEV_GUIDE.md) §7。

---

## 4. 声明式能力网格 (`capabilities`) 矩阵

宿主读 `capabilities` 的作用有两层：
1. **能力徽标**：音源卡片上展示「这个音源支持什么」（全部 14 个已知值都会画徽标）；
2. **功能门控**：只有下面标 ★ 的三项目前会真正**隐藏对应 UI 入口**（源代码 `ModuleManifest.ALL`，未列出的值对徽标外无影响；未声明=入口隐藏；完全不写 `capabilities` 字段=不隐藏任何入口）。

| 能力标识 (`capabilities`) | 声明的意义与 UI 表现 | 门控 | 对应的宿主方法（`apiMap` 的 key） |
|---|---|---|---|
| `search` (基础核心) | 歌曲/歌单/歌手/专辑搜索与实时建议 | — | `cloudsearch`, `search/suggest`, `search/hot/detail` |
| `playback` (基础核心) | 播放器、底栏进度条、预加载缓存与下载队列 | — | `song/url/v1`, `song/url/v1/302`, `song/download/url/v1`, `song/detail` |
| `wordSyncedLyrics` / `lyricsSearch` | 歌词全屏页、沉浸式背景、逐字动效 | — | `lyric/new`(+ 宿主自带 AMLL/插件兜底) |
| `userPlaylists` | 曲库页“我的歌单”分区与歌单详情 | — | `user/playlist`, `playlist/detail`, `playlist/track/all` |
| `artistExplorer` | 歌手主页、热门单曲、歌手唱片作品集 | — | `artist/detail`, `artist/top/song`（建议映射到 `artist/songs`）, `artist/songs`, `artist/album` |
| `albumExplorer` | 专辑主页、唱片介绍与曲目清单 | — | `album` |
| `recommend` | 首页每日推荐卡片、推荐歌单栅格 | ★ | `recommend/songs`, `recommend/resource` |
| `charts` | 发现页排行榜总览与权威榜单 | — | `toplist` |
| `comments` | 播放页评论悬浮球与评论/回复抽屉 | ★ | `comment/new`, `comment/floor` |
| `cloudDrive` | 曲库页用户云盘入口卡片 | ★ | `user/cloud` |
| `qrLogin` | 账号界面扫码授权登录窗口 | —（扫码 UI 常驻，按 `loginMethods` 收） | `login/qr/key`, `login/qr/create`, `login/qr/check` |
| `webviewLogin` | 外部浏览器授权与会话轮询 | — | `login/webview/url`, `login/webview/check`, `login/status` |
| `directoryBrowser` | 自定义目录 / 流派浏览能力徽标（暂无对应宿主页面） | — | — |

> ⚠️ **避免一份文档两套说法**：`loginMethods`（登录页可见哪些登录方式）与 `qrLogin`/`webviewLogin`（能力徽标）是两套机制；当前登录入口显隐听 `loginMethods`。

退出声明：未识别的能力值**忽略而不报错**（前向兼容：新宿主的未来能力不会把旧音源搞崩）。

`capabilities: []` 或仅含未知能力时，已知能力集合就是空集，不会退回“未声明、全部展示”的历史分支；只有字段缺失才采用历史兼容语义。

---

## 5. 曲目数据标准契约 (Track Data Contract)

在 Standard v3 协议中，所有涉及曲目对象的地方（搜索结果、详情、歌单歌曲、歌手热门、专辑歌曲等）均采用统一的扁平化结构。

### 5.1 字段定义与兼容宽容表（与 `TrackJsonMapper` 逐项对齐）

```json
{
  "id": "track_1001",
  "title": "晴天",
  "artist": "周杰伦",
  "album": "叶惠美",
  "durationMs": 269000,
  "coverUrl": "https://img.example.com/cover.jpg"
}
```

宿主解析器（`core/.../music/TrackJsonMapper.kt`，两条解析路径共用的**唯一**映射）的键名兼容机制如下：

| 属性 | 推荐主字段 | 兼容回退候选字段（按顺序检索） | 类型与规则说明 |
|---|---|---|---|
| **歌曲 ID** | `id` | `songId`, `trackId`（无 `song_id`） | 字符串或数字；搜索结果里它是上游**裸 id**，详情/入队场景下宿主会在外层包 `mediaId`（`provider://song/资源ID`），插件回传**什么 id 就保留什么 id** |
| **歌曲名称** | `title` | `name`, `song` | 字符串 |
| **歌手名** | `artist` | `ar[]` / `artists[]`（对象数组，取每个的 `name`，多歌手用 `" / "` 连接）、`artist`（对象 → `name`）、`artist`（纯字符串兜底） | 三种形态都识别，数组形态还能拿到歌手 id（可跳主页） |
| **专辑名** | `album` | `al` / `album`（对象，取 `name`，回退 `title`） | 纯字符串或 `{ "name": "叶惠美" }` 对象；**没有** `album_name` 之类的键 |
| **时长** | `durationMs` | `dt`（毫秒）、`durationSeconds`（秒）、`duration`（历史歧义字段） | 明确单位优先，短音频 `durationMs: 9500` 就是 9.5 秒。只有 `duration` 保留旧规则：`1..9999` 为秒，≥10000 为毫秒；新插件不要用它表示长音频秒数 |
| **封面图片** | `coverUrl` | `cover`, `picture`, `picUrl`；专辑对象的 `picUrl`/`coverUrl`/`cover` | 完整的 HTTP/HTTPS 图片地址 |
| **MV id** | — | `mv`, `mvid` | 曲目上的可选 MV 标识 |

> 曲目对象**只有以上字段**被宿主读取。`publishTime`（发行时间）与 `bitrate`（最高码率）**不在**曲目对象上 —— 它们只在 `song/detail` 响应的详情弹窗解析（键名 `publishTime`/`publishDate`/`releaseDate`、`maxbr`/`bitrate`），请写到详情端点里，写进曲目对象会被忽略。

---

## 6. 核心基础 API 规范

宿主与音源的所有交互均使用 `POST` 请求（`Content-Type: application/json`）。

**请求值类型是协议的一部分：业务参数全部为字符串**，例如 `"type":"1"`、`"limit":"30"`、`"offset":"0"`、`"like":"true"`；不要按 JSON number/boolean 强类型反序列化。`config` 是对象，其值同样为字符串。响应中的计数、布尔、毫秒值仍使用 JSON number/boolean，不要把请求规则套到响应上。

歌单、榜单、歌手、专辑和账号 ID 均支持不透明字符串（UUID、hash、`"001"`），宿主保留原样传回。Kotlin 领域模型新增 `resourceId`，旧 `id: Long` 仅供数值兼容，非数字时为 `0`，不能再拿它作列表键、路由或请求参数。评论、私信、MV 等 Legacy 扩展 ID 仍有数值限制，不等于所有历史端点都已泛化。

### 6.1 搜索歌曲与资源 (`POST /search`)

* **请求参数（旧两参调用可不带分页；分页调用另带字符串 `limit` / `offset`）：**
```json
{
  "keywords": "周杰伦 晴天",
  "type": "1",
  "limit": "30",
  "offset": "0"
}
```
* **`type` 检索类型：**
  * `1`：单曲（默认）
  * `1000`：歌单
  * `100`：歌手
  * `10`：专辑

#### 单曲搜索响应 (`type=1`)：
```json
{
  "code": 0,
  "data": {
    "tracks": [
      {
        "id": "track_1001",
        "title": "晴天",
        "artist": "周杰伦",
        "album": "叶惠美",
        "durationMs": 269000,
        "coverUrl": "https://img.example.com/cover.jpg"
      }
    ],
    "total": 100
  }
}
```
*(同时兼容直接返回 `data: [ ...tracks... ]` 或 `data.songs`；`data.total` 与 `data.hasMore` 会保留到领域结果。分页服务接口已支持 limit/offset，旧调用方不传时插件应提供默认页。)*

#### 歌单搜索响应 (`type=1000`)：
```json
{
  "code": 0,
  "data": {
    "playlists": [
      {
        "id": 9991,
        "title": "周杰伦经典合集",
        "coverUrl": "https://img.example.com/pl.jpg",
        "total": 50,
        "author": "官方编辑"
      }
    ]
  }
}
```

#### 歌手搜索响应 (`type=100`)：
```json
{
  "code": 0,
  "data": {
    "artists": [
      {
        "id": 6452,
        "name": "周杰伦",
        "avatarUrl": "https://img.example.com/jay.jpg",
        "fans": 12000000
      }
    ]
  }
}
```

#### 专辑搜索响应 (`type=10`)：
```json
{
  "code": 0,
  "data": {
    "albums": [
      {
        "id": 101,
        "name": "叶惠美",
        "coverUrl": "https://img.example.com/album.jpg",
        "artist": "周杰伦",
        "publishDate": 1058284800000
      }
    ]
  }
}
```

---

### 6.2 播放直链与流媒体鉴权 (`POST /song/url`)

* **请求参数：**
```json
{
  "id": "track_1001",
  "level": "lossless"
}
```
* **`level` 音质等级：**
  * 宿主 UI 只给用户 4 个档位（`AppModel.qualityLevels`）：`"standard"`、`"exhigh"`（**默认值**）、`"lossless"`、`"hires"`。
  * 另有 4 个透传值会原样出现在 `level` 参数里（当前宿主 UI 不主动请求）：`"higher"`、`"jymaster"`、`"sky"`、`"dolby"` —— 建议插件侧按「能处理就处理、处理不了回落最近的低档位」实现，而不是报错。
  * 桌面端对 `lossless` / `hires` / `jymaster` / `sky`（`LOSSLESS_LEVELS`）会触发「边播边落盘」（引擎无法定位 FLAC over HTTP），插件照常返回直链即可，无需特殊配合。

* **响应体（支持防盗链 Header、实际音质、格式与文件大小透传）：**
```json
{
  "code": 0,
  "data": {
    "url": "https://stream.example.com/audio/track_1001.flac",
    "format": "flac",
    "quality": "lossless",
    "size": 37219528,
    "headers": {
      "User-Agent": "Mozilla/5.0 ...",
      "Referer": "https://example.com"
    }
  }
}
```

> **字段说明：**
> * `url`：音频直链（302 重定向或 CDN 直链均可）。只读取根对象、`data` 对象或 `data` 数组条目的 `url` / `redirectUrl`；必须是 HTTP(S)，失败业务码不会被当成成功。不会深搜封面、验证码或错误消息中的地址。
> * `format`：音频容器格式（`"flac"`, `"mp3"`, `"m4a"`, `"ogg"`, `"wav"`），宿主用于播放格式识别，兼容 `type` / `encodeType`。
> * `quality`：服务端实际返回的音质档位，兼容 `level`（优先）。降级到较低音质时请如实回显，不要盲目复制请求。
> * `size`：音频文件大小（单位：**字节 Bytes**）。宿主据此显示无损容量并进行预估下载进度。
> * `headers`：防盗链请求头（`User-Agent`、`Referer` 等），音频流引擎与离线下载引擎会自动原样携带。
> * `cookie`：鉴权 Cookie 字符串，填后与 `headers.Cookie` 等效（均注入音频流请求头）。
> * `expireAt`：可选，过期的 Unix 毫秒时间戳；兼容 Legacy `expi`（收到响应起的秒数 TTL）。已过期地址不命中缓存，播放地址也不使用无限期旧缓存兜底。

### 6.2.1 歌曲详情（必需，`POST /song/detail`）

请求：`{ "ids": "track_1001,track_1002" }`。`ids` 是逗号分隔字符串，不是 JSON 数组；资源 ID 内不要包含逗号。每条结果的 `id` 必须与请求一致，不要改变大小写或去掉前导零。

```json
{
  "code": 0,
  "data": {
    "tracks": [
      { "id": "track_1001", "title": "晴天", "artist": "周杰伦", "album": "叶惠美", "durationMs": 269000 }
    ]
  }
}
```

批量请求只返回确实存在的曲目；不支持的端点返回 `{"code":-1,"msg":"Unsupported"}`，不要返回成功空壳或 HTML 错误页。

---

### 6.3 歌词与逐字动效 (`POST /lyric`)

CPPlayer 原生集成 **Accompanist Lyrics Core**，全面支持业界主流的高级逐字跳字动效与滚动歌词：

* **请求参数：**
```json
{
  "id": "track_1001"
}
```

#### 形式一：普通滚动歌词 (LRC) / 增强逐字歌词 (ELRC)
```json
{
  "code": 0,
  "data": {
    "lyric": "[00:00.00] 晴天 - 周杰伦\n[00:29.00] 故事的小黄花 从出生那年就飘着\n[00:33.00] 童年的荡秋千 随记忆一直晃到现在"
  }
}
```
*(同时支持 ELRC 词级标签：`[00:29.00]<00:29.00>故<00:29.30>事<00:29.60>的...`)*

#### 形式二：原生 TTML 逐字歌词 (Apple Music / AMLL 规范)
支持直接在 `data.lyric` 或 `data.ttml` 返回 TTML XML 字符串（支持多声部角色与伴唱）：
```json
{
  "code": 0,
  "data": {
    "ttml": "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:01.000\" end=\"00:04.000\"><span begin=\"00:01.000\" end=\"00:02.000\">故事的</span><span begin=\"00:02.000\" end=\"00:03.000\">小黄花</span></p></div></body></tt>"
  }
}
```

#### 形式三：YRC 逐字歌词（网易云）
```json
{
  "code": 0,
  "data": {
    "yrc": "[12580,3470](12580,250,0)故(12830,300,0)事(13130,200,0)的(13330,500,0)小(13830,400,0)黄(14230,800,0)花"
  }
}
```

#### 形式四：KRC 逐字歌词（酷狗明文）
支持酷狗逐字时间戳、`[bg:...]` 伴唱轨与 `[language:...]` 内置翻译注音：
```json
{
  "code": 0,
  "data": {
    "krc": "[1000,3000]<0,1000,0>故<1000,1000,0>事<2000,1000,0>的"
  }
}
```

#### 形式五：多轨分离自动缝合
如果返回网易云等平台的独立轨结构，播放器会自动完成时间戳对齐与注入：
```json
{
  "code": 0,
  "data": {
    "yrc": "[12580,3470](12580,250,0)故(12830,300,0)事...",
    "tlyric": "[00:12.58] The story...",
    "romalrc": "[00:12.58] gu shi..."
  }
}
```

---

## 7. 曲库与互动进阶 API

### 7.1 歌手主页 (`POST /artist/detail`, `POST /artist/songs`, `POST /artist/albums`)

#### 7.1.1 歌手资料 (`POST /artist/detail`)
* **请求：** `{ "id": "6452" }`
* **响应：**
```json
{
  "code": 0,
  "data": {
    "id": 6452,
    "name": "周杰伦",
    "avatar": "https://img.example.com/jay.jpg",
    "briefDesc": "华语流行音乐天王，音乐制作人",
    "alias": ["Jay Chou"],
    "fans": 12000000
  }
}
```
*(兼容字段：`avatarUrl` / `cover` / `picUrl`，`fans` / `fansCount` / `followeds`)*

#### 7.1.2 歌手热门单曲 (`POST /artist/songs`)
* **请求：** `{ "id": "6452", "limit": "50" }`
* **响应：**
```json
{
  "code": 0,
  "data": {
    "tracks": [
      {
        "id": "track_1001",
        "title": "晴天",
        "artist": "周杰伦",
        "album": "叶惠美",
        "durationMs": 269000,
        "coverUrl": "https://img.example.com/cover.jpg"
      }
    ]
  }
}
```

#### 7.1.3 歌手专辑列表 (`POST /artist/albums`)
* **请求：** `{ "id": "6452", "limit": "30" }`
* **响应：**
```json
{
  "code": 0,
  "data": [
    {
      "id": 101,
      "name": "叶惠美",
      "cover": "https://img.example.com/cover101.jpg",
      "publishDate": 1058284800000
    }
  ]
}
```

---

### 7.2 专辑主页 (`POST /album`)

* **请求：** `{ "id": "101" }`
* **响应：**
```json
{
  "code": 0,
  "data": {
    "id": 101,
    "title": "叶惠美",
    "cover": "https://img.example.com/album.jpg",
    "artist": "周杰伦",
    "publishDate": 1058284800000,
    "description": "周杰伦第四张录音室专辑",
    "tracks": [
      {
        "id": "track_101_1",
        "title": "以父之名",
        "artist": "周杰伦",
        "durationMs": 342000,
        "coverUrl": "https://img.example.com/album.jpg"
      }
    ]
  }
}
```

---

### 7.3 用户歌单与歌单详情

#### 7.3.1 用户自建/收藏歌单 (`POST /user/playlist`)
* **请求：** `{ "uid": "12345" }`
* **响应：**
```json
{
  "code": 0,
  "data": [
    {
      "id": 9991,
      "title": "我最爱的华语流行",
      "coverUrl": "https://img.example.com/fav.jpg",
      "total": 68
    }
  ]
}
```

#### 7.3.2 歌单详情 (`POST /playlist/detail`)
* **请求：** `{ "id": "9991" }`
* **响应：**
```json
{
  "code": 0,
  "data": {
    "id": 9991,
    "title": "我最爱的华语流行",
    "coverUrl": "https://img.example.com/fav.jpg",
    "total": 68,
    "author": "我的昵称",
    "description": "精选珍藏私房歌单",
    "tracks": [
      {
        "id": "track_1001",
        "title": "晴天",
        "artist": "周杰伦",
        "durationMs": 269000
      }
    ]
  }
}
```

#### 7.3.3 歌单曲目分页 (`POST /playlist/track/all`)
* **请求：** `{ "id": "9991", "limit": "100", "offset": "0" }`
* **响应：**
```json
{
  "code": 0,
  "data": {
    "tracks": [ ... ],
    "hasMore": true
  }
}
```

---

### 7.4 每日推荐与发现榜单 (`recommend`, `charts`)

#### 7.4.1 每日推荐歌曲 (`POST /recommend/songs`)
* **响应：**
```json
{
  "code": 0,
  "data": {
    "dailySongs": [
      {
        "id": "track_1001",
        "title": "晴天",
        "artist": "周杰伦",
        "album": "叶惠美",
        "durationMs": 269000,
        "coverUrl": "https://img.example.com/cover.jpg"
      }
    ]
  }
}
```

#### 7.4.2 发现榜单总览 (`POST /toplist`)
* **响应：**
```json
{
  "code": 0,
  "data": [
    {
      "id": 1,
      "title": "热歌榜",
      "cover": "https://img.example.com/hot.jpg",
      "description": "全网最热门曲目",
      "tracks": [
        { "first": "晴天", "second": "周杰伦" },
        { "first": "七里香", "second": "周杰伦" }
      ]
    }
  ]
}
```

---

### 7.5 歌曲评论 (`POST /comment/new`, `POST /comment/floor`)

> 宿主读评论列表**只走** `comment/new`（`comment/music` 等老端点已被宿主弃用）；楼层回覆走 `comment/floor`。

* **请求参数（评论列表）：** `{ "id": "track_1001", "type": "0", "pageNo": "1", "pageSize": "20", "sortType": "3" }`
  * `type` 为宿主类型码（0-6，歌曲=0、歌单=4…），由宿主自身传出，插件原样识别即可；
  * `pageNo` / `pageSize` 为分页（宿主把 UI 的 offset 换算成了页码）；
  * `sortType` 为排序方式（该端点唯一认排序的契约）。
* **楼层回覆 (`comment/floor`)：** `{ "id": "track_1001", "parentCommentId": 8888, "type": "0", "limit": "20", "time": "…" }`
* **响应体：**
```json
{
  "code": 0,
  "data": {
    "totalCount": 100,
    "hasMore": false,
    "comments": [
      {
        "id": 8888,
        "content": "前奏一响，青春回来了！",
        "time": 1700000000000,
        "likedCount": 199,
        "user": {
          "userId": 5555,
          "name": "杰迷小明",
          "avatar": "https://img.example.com/avatar.jpg"
        }
      }
    ]
  }
}
```
*(兼容支持 `user.nickname` 与 `user.avatarUrl`)*

---

### 7.6 红心收藏双向同步 (`POST /likelist`, `POST /like`)

* **获取用户红心歌曲 ID 集合 (`POST /likelist`)：**
```json
{
  "code": 0,
  "data": ["track_1001", "track_1002"]
}
```
*(宿主兼容 `data: ["id"]` 纯 ID 数组，以及 `{ "ids": ["id"] }` 对象结构)*

* **标记 / 取消标记红心 (`POST /like`)：**
  * 请求：`{ "id": "track_1001", "like": "true" }`
  * 响应：`{ "code": 0 }`

---

## 8. 账号体系、登录态与风控核验

### 8.1 登录状态检查 (`POST /login/status`)

CPPlayer 会在启动及刷新用户信息时调用此接口。

* **未登录状态：**
```json
{
  "code": 200,
  "data": {
    "code": 200,
    "account": null,
    "profile": null
  }
}
```

* **已登录状态：**
```json
{
  "code": 200,
  "data": {
    "code": 200,
    "account": {
      "id": 987654321
    },
    "profile": {
      "userId": 987654321,
      "nickname": "我的酷狗昵称",
      "avatarUrl": "https://img.example.com/my_avatar.jpg"
    }
  }
}
```

---

### 8.2 二维码扫码登录全流程 (`qrLogin`)

1. **获取二维码标识 (`POST /login/qr/key`)：**
   * 响应：`{ "code": 200, "data": { "unikey": "k_8f9a2b" } }`
2. **生成二维码内容 (`POST /login/qr/create`)：**
   * 请求：`{ "key": "k_8f9a2b" }`
   * 响应：`{ "code": 200, "data": { "qrurl": "https://login.example.com/q/8f9a2b" } }`
3. **轮询检查状态 (`POST /login/qr/check`)：**
   * 请求：`{ "key": "k_8f9a2b" }`
   * **状态响应规范：**
     * `800`：二维码已过期 (`{ "code": 800, "message": "二维码已过期" }`)
     * `801`：等待用户扫码 (`{ "code": 801, "message": "等待扫码" }`)
     * `802`：已扫码，等待在手机上确认 (`{ "code": 802, "message": "已扫码，请在手机确认" }`)
     * `803`：登录成功，返回 Cookie 字符串 (`{ "code": 803, "cookie": "token=xxx; userid=987654321", "message": "登录成功" }`)

---

### 8.3 网页授权登录 (`webviewLogin`) 与风控拦截

#### 8.3.1 网页登录 (`POST /login/webview/url`)
* 响应：`{ "code": 0, "data": { "url": "https://auth.example.com/oauth", "sessionId": "unguessable-one-time-session" } }`
* 宿主打开外部浏览器，不读取浏览器 Cookie，也不拦截任意回调。插件后端负责 OAuth 回调、校验 state、交换令牌，并绑定这一次 sessionId。
* 打开页面后，宿主每 2 秒调用 `POST /login/webview/check`，请求 `{ "sessionId": "unguessable-one-time-session" }`，最多 5 分钟；离页、切登录通道、切音源或登出会取消。
* 等待：`{ "code": 0, "data": { "status": "pending" } }`；成功：`{ "code": 0, "data": { "status": "authorized", "cookie": "session=..." } }`。终态还包括 `expired` / `denied`。**code=0 本身不表示授权完成**。
* 授权完成后仍调用 `login/status` 验证身份。插件也可保存服务端会话而不回传 cookie，宿主不再因「本地没有 Cookie」跳过状态检查；这种共享服务端部署必须自行隔离客户端会话，不能把所有用户绑到一个全局登录态。
* 旧插件只返回 URL 时仍能打开浏览器，但不会自动得到登录结果；应提示用户通过 Cookie 登录或升级插件实现会话接口。

#### 8.3.2 拦截风控与滑动验证码
当密码登录或频繁请求触发平台风控（例如滑块拼图、文字点选）时：
```json
{
  "code": 250,
  "message": "本次环境存在风险，请完成安全核验",
  "data": {
    "verifyUrl": "https://verify.example.com/captcha/slider?token=8f9a2b"
  }
}
```
* **宿主行为：** CPPlayer 自动捕获该异常响应并在账号界面弹出安全卡片，提供 **「打开安全验证页面」** 按钮，用户完成滑块后无缝回到应用重试。

---

## 9. 声明式配置界面 (`settingsSchema`)

若音源需要用户输入配置（如内网 NAS 地址、API Key、自选 CDN、中继代理）：

```json
{
  "settingsSchema": [
    {
      "key": "serverUrl",
      "label": "服务器基地址",
      "type": "string",
      "placeholder": "https://music.nas.local:4533",
      "required": true,
      "description": "Navidrome / Subsonic / 本地音频服务器的完整 URL"
    },
    {
      "key": "apiToken",
      "label": "API Token / 访问密钥",
      "type": "password",
      "required": true
    },
    {
      "key": "streamQuality",
      "label": "首选流媒体音质",
      "type": "select",
      "default": "lossless",
      "options": [
        { "label": "标准 MP3 (128k)", "value": "standard" },
        { "label": "极高 MP3 (320k)", "value": "exhigh" },
        { "label": "无损 FLAC", "value": "lossless" }
      ]
    },
    {
      "key": "enableTranscoding",
      "label": "服务端动态转码",
      "type": "switch",
      "default": "false"
    }
  ]
}
```

> ⚠️ **`default` 必须是 JSON 字符串**（schema 定义 `default: String?`）。写成布尔 `"default": false` 会让 manifest 的 kotlinx 反序列化直接抛异常、**整个音源导入失败**（显示「导入失败」）。即便是 switch 也要写 `"true"` / `"false"`。
>
> 另一个被低估的坑：`required` 只影响 UI 显示，宿主**不做强制校验**——必填缺值时照样传入（会缺少该字段而非报错）。插件后端应自行兜底默认值。

### 控件类型支持：
* `"string"`：普通文本输入框
* `"password"`：密码隐藏输入框
* `"switch"`：Material 3 原生滑动开关
* `"select"`：单选下拉列表（需提供 `options: [{ label, value }]`）

### 宿主透传机制：
1. CPPlayer 在设置页为该音源生成原生表单供用户配置；
2. 每次调用音源服务时：
   * 在 HTTP Request Header 中注入 `X-Provider-Config: {"serverUrl":"...","apiToken":"..."}`；
   * 在 HTTP POST JSON Body 根层平铺注入每个键值（如 `req.body.serverUrl`）；
   * 在 `req.body.config.<key>` 中同步注入一份。
3. **后端无状态**：音源服务无需本地保存用户配置，直接从 Header 或 Body 中读取即可。

配置参与 API 缓存和播放流缓存的隔离键。保存配置后当前音源页面会刷新；服务器地址、令牌变更不会继续命中另一套配置下的结果。不要使用 `id` / `cookie` / `config` 等业务保留键作为设置项名。

---

## 10. 原生 JNI 动态库模块开发 (Rust / C++)

对于不想起本地 HTTP 服务器的高性能音源，推荐打包为原生 JNI 动态链接库。

### 10.1 导出函数契约

原生库需导出标准的 JNI 调用函数（以 C ABI 链接）：

#### C/C++ 签名（符号名必须一模一样 —— JNI 按 `Java_<包名下划线化>_<类名>_<方法名>` 查找）：
```c
JNIEXPORT jstring JNICALL
Java_cp_player_core_provider_JniProvider_nativeCallApi(
    JNIEnv *env,
    jobject thiz,
    jstring j_method,
    jstring j_params_json
);
```

> ⚠️ 方法名是 **`nativeCallApi`**（对应 Kotlin 侧 `JniProvider.nativeCallApi`），不是 `callNative`。符号写错时 `System.load()` 会成功、但**首次调用**才抛 `UnsatisfiedLinkError`；宿主的错误日志里会打印期望的导出符号名，可据此快速定位。

这是进程级 JNI ABI，不是可任意替换宿主 Kotlin 类的稳定 SDK。宿主类全限定名、JNI 签名、OS、CPU ABI 与依赖动态库都要匹配；Windows x86_64 的 DLL 不能放到 Linux 当 SO 用。当前布局只有 `lib/<abi>/`，没有 OS 维度，同 CPU 不同 OS 建议分别发包并使用正确 entryPoint。桌面 Worker 与直接 JNI 都按原始 JSON 透传嵌套 `config` 对象，不要把它当 JSON 字符串再解一层。C++ 不要让异常跨 JNI 边界，Rust 不要让 panic 穿过 FFI；优先返回可解析的错误 JSON。

#### Rust 实现范例：
```rust
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_cp_player_core_provider_JniProvider_nativeCallApi(
    mut env: JNIEnv,
    _class: JClass,
    method: JString,
    params: JString,
) -> jstring {
    let method_str: String = env.get_string(&method).unwrap().into();
    let params_str: String = env.get_string(&params).unwrap().into();

    let params_json: serde_json::Value =
        serde_json::from_str(&params_str).unwrap_or(serde_json::json!({}));

    // 业务调度分发并生成响应 JSON
    let resp = crate::standard_api::handle_api(&method_str, &params_json);
    let resp_str = resp.to_string();

    env.new_string(resp_str).unwrap().into_raw()
}
```

> 另外两个可选导出是 `Java_cp_player_core_provider_JniProvider_startNativeServer(host, port)` 与 `Java_cp_player_core_provider_JniProvider_analyzeAudioFile(path)`。纯 API 插件只实现 `nativeCallApi` 即可；若只提供本地 HTTP 服务，则需由 `startNativeServer` 启动，宿主在找不到 `nativeCallApi` 时才回退到该 HTTP 服务。未提供音频分析导出不影响普通 API 调用。

---

## 11. 最简 Node.js 参考实现 (开箱即用)

先把这份最小 `manifest.json`（与下面 server.js 的端点一一对应）和 server.js 放在同一目录：

```json
{
  "id": "demo-standard-v3",
  "name": "Demo 音源",
  "version": "1.0.0",
  "type": "http",
  "entryPoint": "http://127.0.0.1:3000",
  "capabilities": ["search", "playback"],
  "apiMap": {
    "cloudsearch": "search",
    "song/url/v1": "song/url",
    "song/url/v1/302": "song/url",
    "song/download/url/v1": "song/url",
    "song/detail": "song/detail",
    "lyric/new": "lyric"
  }
}
```

```javascript
// server.js
const express = require('express');
const app = express();
app.use(express.json());

const demoTrack = {
    id: "demo_1001", title: "示例曲目", artist: "示例歌手", album: "示例专辑",
    durationMs: 215000
};

// 1. 搜歌。请求值都是字符串；响应数值保持数字。
app.post('/search', (req, res) => {
    const { type = "1" } = req.body;
    res.json({
        code: 0,
        data: {
            tracks: type === "1" && Number(req.body.offset || "0") === 0 ? [demoTrack] : [],
            total: type === "1" ? 1 : 0,
            hasMore: false
        }
    });
});

// 2. 无论从搜索点击还是恢复队列，都能按同一个 ID 取详情。
app.post('/song/detail', (req, res) => {
    const ids = String(req.body.ids || "").split(",");
    res.json({ code: 0, data: { tracks: ids.includes(demoTrack.id) ? [demoTrack] : [] } });
});

// 3. 音频直链
app.post('/song/url', (req, res) => {
    const { id, level = "standard" } = req.body;
    if (id !== demoTrack.id) return res.json({ code: 404, msg: "Track not found" });
    const url = process.env.DEMO_AUDIO_URL;
    if (!url) return res.json({ code: 500, msg: "Set DEMO_AUDIO_URL to a real HTTP(S) audio URL" });
    res.json({
        code: 0,
        data: {
            url,
            format: "mp3",
            quality: "standard",
            headers: {
                "User-Agent": "CPPlayer/Standard-v3"
            }
        }
    });
});

// 4. 可选歌词
app.post('/lyric', (req, res) => {
    res.json({
        code: 0,
        data: {
            lyric: "[00:00.00] 示例歌词\n[00:01.00] 正在播放示例音乐"
        }
    });
});

app.listen(3000, () => console.log('CPPlayer Standard v3 服务已在 3000 端口启动'));
```

安装依赖：`npm install express`，配置 `DEMO_AUDIO_URL` 为你有权使用的真实 HTTP(S) 音频再运行 `node server.js`。示例元信息只是演示，接入真实音源时必须返回与实际音频一致的标题、时长、音质与格式，不能把任意 MP3 标成无损。

---

## 12. 插件打包与安装验证

1. **打包 ZIP**：将 `manifest.json`（必须位于 ZIP **根目录**）及二进制库/代码打至根目录；
2. **重命名后缀**（可选）：将 `.zip` 后缀修改为 `.cpm`。**注意**：桌面端导入对话框**只过滤 `.zip`**，`.cpm` 文件在桌面端选不中；Android 端经 SAF 不受后缀影响。想桌面端走「双击导入」的用户建议**保留 `.zip`**（文件名里仍可带 `.cpm` 字样，如 `myprovider.cpm.zip`）；
3. **导入应用**：
   * 打开 CPPlayer，进入 **设置 → 音源管理**；
   * 点击右上角 **「+」** 或顶部 **「导入模块」**，选择你的 `.zip` / `.cpm` 文件；
4. **验证能力**：
   * 导入后音源卡片将展示出声明的全部能力徽标；
   * 前往搜索页搜索歌曲，点击即可起播（首次会拉取 `login/status` 等身份接口，未实现时明确返回 `{"code":-1,"msg":"Unsupported"}`，不要伪造已登录状态）。

### 当前边界与验收

- 最小 3 接口只保证搜歌、解析详情和取流闭环；歌单编辑、登录、评论等必须另实现对应端点，能力徽标本身不会补齐功能。
- 搜索服务层已接受 `limit` / `offset` 并保留 `total` / `hasMore`，但当前主搜索页面仍是单次查询，没有续页入口。不要把服务分页支持等同于页面支持无限滚动。
- 评论、私信、MV 等 Legacy 路径仍有数值 ID 假设；第三方平台的分享链接也尚无通用回传契约，部分旧入口仍使用网易云链接。这些不属于任意平台都已完整覆盖的能力。
- 发布前至少验证：短音频时长、多个非数字 ID 不重名、歌单最后一页、切账号/服务地址后不串缓存、链接过期后重新取流、网页登录 pending/成功/拒绝/超时。
- Kotlin 编译与解析器测试不能证明原生库可用；JNI 应逐 OS/ABI 装包测试，网页登录还需用真实后端和浏览器验证回调、会话隔离与退出取消。

> **导入失败怎么排查**：宿主在音源管理页 / 诊断页会给出 `ModuleManager.lastLoadError` 的具体文案（manifest 缺字段、类型不支持、ABI 不匹配、entryPoint 不存在等）；对应分支见源码 `ModuleManager.kt` 的导入路径错误文案。
