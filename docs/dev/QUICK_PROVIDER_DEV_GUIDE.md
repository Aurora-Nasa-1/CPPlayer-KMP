# 5 分钟上手：现代音源开发与第三方平台移植指南 (Provider Standard v3)

> **适用场景：** 为 CPPlayer 开发全新的轻量音乐源，或将 QQ 音乐、酷狗、Bilibili、Subsonic/Navidrome、YouTube Music、本地/自建源移植到 CPPlayer。
> 
> 如果你要开发的是**网易云原生全功能逆向音源**，请查阅 [网易云 Legacy 完整接口参考手册](PROVIDER_DEV_GUIDE.md)。

---

## 1. 核心理念：开发者减负

在 CPPlayer 现代标准协议（Standard v3）下，第三方音源开发已彻底解绑复杂的网易云深层结构：
* **最简契约：** 仅需实现 **2 ~ 3 个基础 API**（搜歌、取播放直链、歌词）即可作为一个完整可用的音源。
* **扁平数据结构：** 不再需要拼凑 `songs[0].al.picUrl`、`ar[0].name` 或 `dt` 毫秒时长，直接返回扁平的 `title`, `artist`, `album`, `coverUrl`, `duration`。
* **UI 智能自适应隐藏：** 通过声明 `capabilities`（能力网格），CPPlayer 会**物理隐藏**不支持的页面入口（如云盘卡片、扫码登录、评论区），不留任何死区和报错。
* **原生免写代码的设置页：** 在 `manifest.json` 声明 `settingsSchema`，CPPlayer 自动为你渲染原生 Material 3 设置表单，并自动将用户填写的配置注入到每个请求中。

---

## 2. 快速起步：最简音源包结构

一个音源插件实质是一个 `.zip` 压缩包，解压后包含根目录的 `manifest.json`：

```
my-custom-provider.zip
└── manifest.json
```

### 2.1 manifest.json 最小配置

```json
{
  "id": "my-music-source",
  "name": "我的自定义音源",
  "version": "1.0.0",
  "type": "http",
  "entryPoint": "http://127.0.0.1:3000",
  "contract": "standard",
  "capabilities": [
    "search",
    "playback"
  ],
  "apiMap": {
    "cloudsearch": "search",
    "song/url/v1": "song/url",
    "lyric/new": "lyric"
  }
}
```

* `contract`: 声明为 `"standard"`，采用现代扁平模型；
* `capabilities`: 显式声明音源拥有的能力。例如只写 `["search", "playback"]`，CPPlayer 将自动隐藏云盘、日推、评论等无关入口，纯净体验。
* `entryPoint`: 音源服务的基地址。

---

## 3. 必须实现的核心 API (只有 2 个！)

CPPlayer 会向 `{entryPoint}/{apiMap映射的方法名}` 发起 `POST` 请求（`Content-Type: application/json`）。

### 3.1 搜索歌曲 (`POST /search`)

* **请求体：**
```json
{
  "keywords": "周杰伦 晴天",
  "type": 1,
  "limit": 30,
  "offset": 0
}
```

* **响应体（扁平直观）：**
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

> **字段说明：**
> * `id`: 音源内部唯一的歌曲字符串 ID；
> * `title`: 歌曲名称（也兼容 `name`）；
> * `artist`: 歌手名称（支持多个歌手以 `" / "` 分隔）；
> * `album`: 专辑名称（可为纯字符串，或 `{ "name": "叶惠美" }`）；
> * `duration`: 时长（秒或毫秒均可，CPPlayer 自动换算）；
> * `coverUrl`: 封面图片 URL（也兼容 `cover` / `picUrl`）。

---

### 3.2 获取播放音频直链 (`POST /song/url`)

* **请求体：**
```json
{
  "id": "track_1001",
  "level": "standard"
}
```

* **响应体：**
```json
{
  "code": 0,
  "data": {
    "url": "https://stream.example.com/audio/track_1001.flac",
    "format": "flac",
    "quality": "lossless",
    "headers": {
      "User-Agent": "Mozilla/5.0 ...",
      "Referer": "https://example.com"
    }
  }
}
```

> 如果需要防盗链 Referer 或特定的 Cookie，只需在 `data.headers` 中返回，CPPlayer 的音频引擎会自动携带这些请求头进行串流播放。

---

### 3.3 歌词获取 (`POST /lyric`)（可选）

* 如果你的音源不提供歌词，甚至**无需实现**此接口！
  * CPPlayer 会自动启用内置的 **AMLL 歌词库**与**本地边车歌词**，或通过已安装的歌词源插件自动按歌名+歌手寻词。
* 若音源自行提供歌词，返回 LRC 文本即可：
```json
{
  "code": 0,
  "data": {
    "lyric": "[00:00.00] 晴天 - 周杰伦\n[00:29.00] 故事的小黄花..."
  }
}
```

---

## 4. 进阶功能一：声明式自定义设置界面 (Settings Schema)

很多自建源（如 Subsonic、Navidrome、WebDAV）或代理源需要用户输入服务器地址、API Token 或首选音质。
你**不需要编写任何 UI 代码**，只要在 `manifest.json` 中定义 `settingsSchema`：

```json
{
  "settingsSchema": [
    {
      "key": "serverUrl",
      "label": "服务器地址",
      "type": "string",
      "placeholder": "https://music.nas.local:4533",
      "required": true,
      "description": "你的 Navidrome 或 Subsonic 服务器完整地址"
    },
    {
      "key": "apiToken",
      "label": "API Token / 访问令牌",
      "type": "password",
      "required": true
    },
    {
      "key": "preferredQuality",
      "label": "默认音质",
      "type": "select",
      "default": "lossless",
      "options": [
        { "label": "标准 (128kbps)", "value": "standard" },
        { "label": "高品质 (320kbps)", "value": "exhigh" },
        { "label": "无损 (FLAC)", "value": "lossless" }
      ]
    },
    {
      "key": "enableProxy",
      "label": "使用内置代理中继",
      "type": "switch",
      "default": false
    }
  ]
}
```

### 宿主行为
1. 用户在应用内点击 **设置 → 音源管理 → 该音源菜单 →「音源配置」**，会自动打开原汁原味的 Material 3 表单页面供用户填写；
2. CPPlayer 每次调用你的音源服务时：
   * 会在 HTTP 请求头中携带 `X-Provider-Config: {"serverUrl":"...","apiToken":"..."}`；
   * 会自动将配置合并到 POST 请求的 JSON Body 根层；
   * 你的服务可以直接通过 `req.headers['x-provider-config']` 或 `req.body.serverUrl` 读取用户配置，**后端服务完全无状态**！

---

## 5. 进阶功能二：按需开启高级能力

CPPlayer 拥有丰富的音乐功能，只需在 `capabilities` 中按需添加即可解锁对应 UI：

| 能力标识 | 作用 | 解锁的 UI 表现 |
|---|---|---|
| `search` | 搜索歌曲/歌单 | 搜索页面 |
| `playback` | 音频播放直链 | 播放控制条与播放器 |
| `recommend` | 每日推荐 / 推荐歌单 | 首页日推卡片、推荐歌单栅格 |
| `comments` | 歌曲评论 | 播放详情页评论按钮与评论抽屉 |
| `cloudDrive` | 用户云盘 | 曲库页云盘入口卡片 |
| `charts` | 排行榜与榜单 | 发现页排行榜列表 |
| `userPlaylists` | 用户歌单与收藏 | 曲库页“我的歌单”分区 |
| `webviewLogin` | 网页端授权登录 | 账号界面「网页登录」胶囊与直达入口 |

如果你的音源没有评论或云盘功能，只需**不声明**它们，CPPlayer 将干净整洁地物理移除对应入口，绝对不会让用户点到 500 或空白页。

---

## 6. 进阶功能三：WebView 网页登录与安全验证码 (Standard v3)

很多第三方音乐平台（如 QQ 音乐、酷狗、Bilibili 或海外流媒体）采用了严格的人机风控或仅支持网页端 OAuth/扫码授权。Standard v3 规范原生支持 **WebView 登录** 与 **安全验证码拦截流**。

### 6.1 声明 WebView 登录通道

在 `manifest.json` 中，通过 `loginMethods` 或 `capabilities` 声明支持网页登录：

```json
{
  "capabilities": ["search", "playback", "webviewLogin"],
  "loginMethods": ["phone", "qr", "webview"],
  "apiMap": {
    "login/webview/url": "login/webview/url",
    "captcha/verify/url": "captcha/verify/url",
    "captcha/verify/check": "captcha/verify/check"
  }
}
```

* `loginMethods`: 支持 `qr`（扫码）、`phone`（手机验证码）、`email`（邮箱）、`cookie`（Cookie）、`webview`（或 `web`，网页登录）。
* 声明后，CPPlayer 账号界面会自动呈现对应的登录通道切换器。

### 6.2 获取 WebView 登录地址 (`POST /login/webview/url`)

* **响应体：**
```json
{
  "code": 0,
  "data": {
    "url": "https://auth.example.com/oauth/authorize?client_id=xxx"
  }
}
```
* CPPlayer 会在桌面端或移动端以系统原生 Web 容器（桌面调起系统浏览器/WebView，移动端调起系统 View）打开该授权地址，用户完成授权后即可登录。

### 6.3 安全验证码与人机风控拦截机制

当用户使用手机号或密码登录时，若触发平台风控（例如网易云 `250` 风控、Bilibili 人机验证、滑动拼图等），服务端可直接在响应中返回验证码链接：

* **风控响应示例：**
```json
{
  "code": 250,
  "message": "本次登录环境存在风险，请完成安全核验",
  "data": {
    "verifyUrl": "https://verify.example.com/captcha/slider?token=8f9a2b"
  }
}
```
> **兼容支持：**
> * 响应码：`250`、`804`、`4001` 或任何非 0/200 错误码；
> * 验证码字段：支持在根层或 `data` 下返回 `verifyUrl`、`url`、`verifyContentUrl`、`captchaUrl` 或 `webUrl`。

**宿主行为：**
1. CPPlayer 会自动捕获风控响应，提取核验网址；
2. 账号界面即时呈现红框安全核验卡片，提供 **「打开验证页面」**（直接唤起系统 WebView/浏览器进行滑块或文字点选）与 **「复制验证链接」**；
3. 用户在网页中完成核验后，点击关闭或重试，即可无缝继续登录流程。

---

## 7. 最简可运行示例：Node.js 实现 (小于 50 行代码)

```javascript
// server.js
const express = require('express');
const app = express();
app.use(express.json());

// 1. 搜索
app.post('/search', (req, res) => {
    const { keywords } = req.body;
    console.log('搜索关键词:', keywords);
    res.json({
        code: 0,
        data: {
            tracks: [
                {
                    id: "demo_1",
                    title: `示例歌曲: ${keywords}`,
                    artist: "未知艺术家",
                    album: "示例专辑",
                    duration: 215,
                    coverUrl: "https://p2.music.126.net/6y-UleORITEDbvrOLAL-vQ==/109951163962024848.jpg"
                }
            ]
        }
    });
});

// 2. 播放链接
app.post('/song/url', (req, res) => {
    const { id } = req.body;
    // 从 Header 或 Body 读取用户配置
    const config = req.headers['x-provider-config'] ? JSON.parse(req.headers['x-provider-config']) : {};
    console.log('获取音频:', id, '用户配置:', config);

    res.json({
        code: 0,
        data: {
            url: "https://www.w3schools.com/html/horse.mp3",
            format: "mp3",
            quality: "standard"
        }
    });
});

app.listen(3000, () => console.log('音源服务已就绪: http://127.0.0.1:3000'));
```

---

## 8. 歌词源插件开发 (Lyrico Plugin API)

CPPlayer 兼容开源标准的 **Lyrico Plugin API**，允许开发者编写纯 JavaScript 脚本来为播放器提供第三方歌词寻词服务（如 QQ 音乐、网易云、酷狗、Musixmatch 等）。

### 8.1 歌词插件包目录结构

一个歌词插件可以是一个 `.zip` 压缩包，或者单 `.js` 文件：

```
my-lyrics-plugin.zip
├── manifest.json
└── plugin.js
```

### 8.2 manifest.json 格式

```json
{
  "id": "my-lyrics-source",
  "name": "我的第三方歌词源",
  "versionName": "1.0.0",
  "versionCode": 1,
  "author": "Developer",
  "description": "提供高质量 LRC 与逐字歌词",
  "entry": "plugin.js",
  "capabilities": ["searchSongs", "getLyrics"]
}
```

### 8.3 plugin.js 核心逻辑示例

插件运行在内置的 JS 沙箱环境中，支持 ES5/ES6 语法并通过 `Platform.*` 发起异步网络请求：

```javascript
// plugin.js

/**
 * 1. 搜索歌曲
 * @param {string} query 关键词（歌名 + 歌手）
 * @returns {Array<{id: string, name: string, artist: string, album: string}>}
 */
function searchSongs(query) {
    var response = Platform.http.get("https://api.example.com/search?q=" + encodeURIComponent(query));
    var json = JSON.parse(response.body);
    
    return json.results.map(function(item) {
        return {
            id: item.song_id,
            name: item.title,
            artist: item.artist_name,
            album: item.album_title
        };
    });
}

/**
 * 2. 获取歌词
 * @param {string} id 歌曲 ID
 * @returns {string} LRC 歌词文本或 TTML/YRC 逐字歌词
 */
function getLyrics(id) {
    var response = Platform.http.get("https://api.example.com/lyrics?id=" + encodeURIComponent(id));
    var json = JSON.parse(response.body);
    return json.lrc; // 返回标准 LRC 字符串，如 "[00:01.00] 歌词内容..."
}
```

---

## 9. 插件打包与安装验证

1. **打包为 `.zip`**：将 `manifest.json` 及相关代码/静态文件打包至根目录；
2. **重命名后缀（推荐）**：可将 `.zip` 重命名为 `.cpm`（CPPlayer Module 专有识别后缀）；
3. **在应用中导入**：
   - **音源插件**：进入应用内 **设置 → 音源管理**，点击右上角 **「+」** 或顶部 **「导入模块」**；
   - **歌词插件**：进入应用内 **设置 → 歌词来源**，点击 **「导入歌词源插件」**；
4. **导入后验证**：导入成功后插件将立即就绪，音源管理中可直接启用、配置或拖拽调整优先级。

