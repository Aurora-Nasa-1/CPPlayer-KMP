# CPPlayer 现代音源开发与第三方平台移植开发指南 (Provider Standard v3)

> **适用场景：** 为 CPPlayer 开发全新的轻量音乐源，或将 QQ 音乐、酷狗、酷我、Bilibili、Subsonic/Navidrome、YouTube Music、本地/自建源移植到 CPPlayer。  
> **数据源标准：** 本文档内容**完全直接源自 CPPlayer 宿主核心源码**（`UnifiedMusicSourceImpl.kt`, `MusicSourceFromApi.kt`, `LyricsParser.kt`, `ModuleManifest.kt`, `HttpProvider.kt`, `PlatformSupport.kt` 等）。  
> *注：如果你要开发的是针对网易云历史二进制私有加密的逆向接口包，请查阅 [网易云 Legacy 完整接口参考手册](PROVIDER_DEV_GUIDE.md)。*

---

## 1. 核心架构设计理念

CPPlayer Standard v3 协议规范的核心理念是 **“开发者减负、能力渐进式扩展、UI 智能自适应”**：

1. **最简核心契约（极简起步）：**  
   任何音源只需实现 **2 ~ 3 个基础 API**（搜歌 `POST /search`、取流直链 `POST /song/url`、可选歌词 `POST /lyric`），即可在 CPPlayer 拥有完整的听歌、串流、预加载与下载体验。
2. **现代扁平数据模型：**  
   告别历史平台繁复的深度嵌套（如 `songs[0].al.picUrl`, `ar[0].name`, `dt` 毫秒时长），直接返回扁平字段：`title`, `artist`, `album`, `coverUrl`, `duration`。
3. **声明式能力网格（未声明即物理隐藏）：**  
   音源通过 `manifest.json` 的 `capabilities` 声明自己支持的功能。对于未声明的能力（如评论、日推、云盘、网页登录），CPPlayer 会**在界面层物理隐藏对应入口**，不留死角，绝不会让用户触发 404 或空白错误页。
4. **跨语言运行时支持（HTTP / JNI / Binary）：**  
   支持 Node.js/Python/Go/Java 编写的 HTTP 服务（远程或本地），也支持 Rust/C++ 编译出的轻量原生 JNI 动态库（`.dll` / `.so`），兼顾开发便捷度与极致性能。
5. **免写 UI 代码的 Material 3 设置页：**  
   在 `manifest.json` 中声明 `settingsSchema`，CPPlayer 自动渲染原生表单并自动注入配置，后端完全无状态。

---

## 2. 音源插件包规范与目录结构

一个标准 CPPlayer 音源插件是一个 `.zip` 压缩包（推荐重命名后缀为 `.cpm`，即 CPPlayer Module）。

### 2.1 运行时类型 (`type`)

CPPlayer 支持三种音源运行时类型：

| 类型 (`type`) | 运行方式 | 适用场景 | `entryPoint` 定义 |
|---|---|---|---|
| `"http"` | 远程或本地 HTTP/HTTPS 服务 | Python / Node.js / Go / 云端或内网部署服务 | 如 `"http://127.0.0.1:3000"` 或 `"https://api.example.com"` |
| `"jni"` | 宿主进程直接动态加载动态链接库 | Rust / C++ / C 高性能跨平台模块 | 库文件名，如 `"libkugou_music_rs.so"` 或 `"kugou_music_rs.dll"` |
| `"binary"` | 宿主自动拉起本地子进程守护执行 | Go / Rust 编译的独立本地单二进制可执行文件 | 可执行文件名，如 `"my_server.exe"` |

### 2.2 打包目录布局

#### HTTP 音源包：
```
my-http-provider.zip (或 .cpm)
└── manifest.json
```

#### 原生 JNI 动态库音源包（多平台跨 ABI 支持）：
宿主加载时优先按 `lib/<平台ABI>/<entryPoint>` 寻址，找不到时回退到根目录 `<entryPoint>`：
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
    "toplist/detail": "toplist",
    "artist/detail": "artist/detail",
    "artist/songs": "artist/songs",
    "artist/albums": "artist/albums",
    "album": "album",
    "album/detail": "album",
    "user/playlist": "user/playlist",
    "playlist/detail": "playlist/detail",
    "playlist/track/all": "playlist/track/all",
    "recommend/songs": "recommend/songs",
    "comment/music": "comment/music",
    "login/qr/key": "login/qr/key",
    "login/qr/create": "login/qr/create",
    "login/qr/check": "login/qr/check",
    "login/status": "login/status",
    "login/webview/url": "login/webview/url"
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
| `contract` | `String` | **必选** | **必须固定填 `"standard"`**，声明采用现代 Standard v3 规范 |
| `minHostApiVersion` | `Int` | 可选 | 宿主最低支持版本（填 `1`） |
| `capabilities` | `Array<String>` | **强烈推荐** | 声明音源支持的能力网格清单，宿主据此自动裁剪 UI 入口 |
| `loginMethods` | `Array<String>` | 可选 | 支持的登录通道：`"qr"`(扫码), `"webview"`(网页), `"phone"`, `"cookie"` |
| `apiMap` | `Map<String, String>` | 可选 | 端点重映射表（将宿主内部标准方法映射为你的自定义端点） |
| `settingsSchema` | `Array<Object>` | 可选 | 声明式设置表单定义 |

---

## 4. 声明式能力网格 (`capabilities`) 矩阵

宿主通过检查音源声明的 `capabilities` 来动态开启或完全隐藏界面元素：

| 能力标识 (`capabilities`) | 解锁的功能与 UI 表现 | 对应的 Standard v3 API |
|---|---|---|
| `search` (基础核心) | 歌曲/歌单/歌手/专辑搜索与实时建议 | `POST /search` |
| `playback` (基础核心) | 播放器、底栏进度条、预加载缓存与下载队列 | `POST /song/url` |
| `wordSyncedLyrics` / `lyricsSearch` | 歌词全屏页、沉浸式背景、逐字动效 | `POST /lyric` |
| `userPlaylists` | 曲库页“我的歌单”分区与歌单详情 | `POST /user/playlist`, `playlist/detail`, `playlist/track/all` |
| `artistExplorer` | 歌手主页、热门单曲、歌手唱片作品集 | `POST /artist/detail`, `artist/songs`, `artist/albums` |
| `albumExplorer` | 专辑主页、唱片介绍与曲目清单 | `POST /album` |
| `recommend` | 首页每日推荐歌曲卡片、推荐歌单栅格 | `POST /recommend/songs`, `recommend/resource` |
| `charts` | 发现页排行榜总览与权威榜单 | `POST /toplist` |
| `comments` | 播放页评论悬浮球与评论/回复抽屉 | `POST /comment/music`, `comment/floor` |
| `cloudDrive` | 曲库页用户云盘入口卡片 | `POST /user/cloud` |
| `qrLogin` | 账号界面扫码授权登录窗口 | `login/qr/key`, `login/qr/create`, `login/qr/check` |
| `webviewLogin` | 账号界面「网页登录」胶囊与原生态授权 | `login/webview/url`, `captcha/verify/*` |

---

## 5. 曲目数据标准契约 (Track Data Contract)

在 Standard v3 协议中，所有涉及曲目对象的地方（搜索结果、详情、歌单歌曲、歌手热门、专辑歌曲等）均采用统一的扁平化结构。

### 5.1 字段定义与兼容宽容表 (直接取自 `TrackJsonMapper`)

```json
{
  "id": "track_1001",
  "title": "晴天",
  "artist": "周杰伦",
  "album": "叶惠美",
  "duration": 269,
  "coverUrl": "https://img.example.com/cover.jpg",
  "publishTime": 1058284800000,
  "bitrate": 320000
}
```

宿主源码解析器具备业界顶级的容错映射机制：

| 属性 | 推荐主字段 | 兼容回退候选字段（按顺序检索） | 类型与规则说明 |
|---|---|---|---|
| **歌曲 ID** | `id` | `songId`, `song_id` | 字符串或数字，如 `"track_1001"` 或 `1001` |
| **歌曲名称** | `title` | `name`, `song` | 字符串 |
| **歌手名** | `artist` | `ar[].name`, `artists[].name`, `author` | 支持纯字符串；支持数组自动用 `" / "` 连接 |
| **专辑名** | `album` | `album.name`, `al.name`, `album_name` | 支持纯字符串，或 `{ "name": "叶惠美" }` 对象 |
| **时长** | `duration` | `dt`, `timeLength`, `time_length`, `durationMs` | **秒或毫秒自动智能识别**：数值 > 10000 自动折算为秒 |
| **封面图片** | `coverUrl` | `cover`, `picUrl`, `imgUrl`, `al.picUrl` | 完整的 HTTP/HTTPS 图片地址 |
| **发行时间** | `publishTime` | `publishDate`, `publish_time` | 13 位毫秒时间戳（可选） |
| **最高码率** | `bitrate` | `maxBitrate`, `br` | 音频最高 bps，如 `320000`（可选） |

---

## 6. 核心基础 API 规范

宿主与音源的所有交互均使用 `POST` 请求（`Content-Type: application/json`）。

### 6.1 搜索歌曲与资源 (`POST /search`)

* **请求参数：**
```json
{
  "keywords": "周杰伦 晴天",
  "type": 1,
  "limit": 30,
  "offset": 0
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
        "duration": 269,
        "coverUrl": "https://img.example.com/cover.jpg"
      }
    ],
    "total": 100
  }
}
```
*(同时兼容直接返回 `data: [ ...tracks... ]` 或 `data.songs`)*

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
* **`level` 音质等级列表：**
  * `"standard"`：标准音质 (128kbps MP3/AAC)
  * `"higher"`：较高音质 (192kbps)
  * `"exhigh"`：极高音质 (320kbps MP3)
  * `"lossless"`：无损音频 (FLAC / 16Bit 44.1kHz)
  * `"hires"`：Hi-Res 高解析音频 (24Bit 96kHz+)
  * `"jymaster"`：超清母带级音频
  * `"sky"`：沉浸环绕声
  * `"dolby"`：杜比全景声

* **响应体（支持防盗链 Header 与文件大小透传）：**
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
> * `url`：音频直链（302 重定向或 CDN 直链均可）。
> * `format`：音频容器格式（`"flac"`, `"mp3"`, `"m4a"`, `"ogg"`, `"wav"`）。
> * `size`：音频文件大小（单位：**字节 Bytes**）。宿主据此显示无损容量并进行预估下载进度。
> * `headers`：防盗链请求头。若音源直链对 User-Agent、Referer、Cookie 有严格校验，填在此处，CPPlayer 的音频流引擎与离线下载引擎会自动原样携带。

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
* **请求：** `{ "id": 6452 }`
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
* **请求：** `{ "id": 6452, "limit": 50 }`
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
        "duration": 269,
        "coverUrl": "https://img.example.com/cover.jpg"
      }
    ]
  }
}
```

#### 7.1.3 歌手专辑列表 (`POST /artist/albums`)
* **请求：** `{ "id": 6452, "limit": 30 }`
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

* **请求：** `{ "id": 101 }`
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
        "duration": 342,
        "coverUrl": "https://img.example.com/album.jpg"
      }
    ]
  }
}
```

---

### 7.3 用户歌单与歌单详情

#### 7.3.1 用户自建/收藏歌单 (`POST /user/playlist`)
* **请求：** `{ "uid": 12345 }`
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
* **请求：** `{ "id": 9991 }`
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
        "duration": 269
      }
    ]
  }
}
```

#### 7.3.3 歌单曲目分页 (`POST /playlist/track/all`)
* **请求：** `{ "id": 9991, "limit": 100, "offset": 0 }`
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
        "duration": 269,
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

### 7.5 歌曲评论 (`POST /comment/music`, `POST /comment/floor`)

* **请求参数：** `{ "id": "track_1001", "limit": 20, "offset": 0 }`
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
  * 请求：`{ "id": "track_1001", "like": true }`
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
* 响应：`{ "code": 0, "data": { "url": "https://auth.example.com/oauth" } }`
* 宿主直接唤起系统 WebView / 外部浏览器完成 OAuth 流程。

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
      "default": false
    }
  ]
}
```

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

---

## 10. 原生 JNI 动态库模块开发 (Rust / C++)

对于不想起本地 HTTP 服务器的高性能音源，推荐打包为原生 JNI 动态链接库。

### 10.1 导出函数契约

原生库需导出标准的 JNI 调用函数（以 C ABI 链接）：

#### C/C++ 签名：
```c
JNIEXPORT jstring JNICALL
Java_cp_player_core_provider_JniProvider_callNative(
    JNIEnv *env,
    jobject thiz,
    jstring j_method,
    jstring j_params_json
);
```

#### Rust 实现范例：
```rust
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_cp_player_core_provider_JniProvider_callNative(
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

---

## 11. 最简 Node.js 参考实现 (开箱即用)

```javascript
// server.js
const express = require('express');
const app = express();
app.use(express.json());

// 1. 搜歌
app.post('/search', (req, res) => {
    const { keywords, limit = 30, offset = 0 } = req.body;
    res.json({
        code: 0,
        data: {
            tracks: [
                {
                    id: "demo_1001",
                    title: `歌曲: ${keywords}`,
                    artist: "示例歌手",
                    album: "示例专辑",
                    duration: 215,
                    coverUrl: "https://p2.music.126.net/6y-UleORITEDbvrOLAL-vQ==/109951163962024848.jpg"
                }
            ],
            total: 1
        }
    });
});

// 2. 音频直链
app.post('/song/url', (req, res) => {
    const { id, level = "standard" } = req.body;
    res.json({
        code: 0,
        data: {
            url: "https://www.w3schools.com/html/horse.mp3",
            format: "mp3",
            quality: level,
            size: 2621440,
            headers: {
                "User-Agent": "CPPlayer/Standard-v3"
            }
        }
    });
});

// 3. 歌词
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

---

## 12. 插件打包与安装验证

1. **打包 ZIP**：将 `manifest.json` 及二进制库/代码打至根目录；
2. **重命名后缀**：推荐将 `.zip` 后缀修改为 `.cpm`；
3. **导入应用**：
   * 打开 CPPlayer，进入 **设置 → 音源管理**；
   * 点击右上角 **「+」** 或顶部 **「导入模块」**，选择你的 `.cpm` / `.zip` 文件；
4. **验证能力**：
   * 导入后音源卡片将展示出声明的全部能力徽标；
   * 前往搜索页搜索歌曲，点击即可秒级起播。
