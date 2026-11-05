# CPPlayer JS 音源插件开发指南 (Provider Standard JS v1)

> **适用场景：** 基于 JavaScript / TypeScript 开发免编译、轻量、跨全平台（Android + Desktop）即插即用的 CPPlayer 音源插件。  
> **底层引擎：** 本宿主采用原生 **QuickJS** 引擎（全平台 C 绑定，支持完整 ES2023、异步 Promise / async / await 与微任务调度）。

---

## 1. 核心架构与设计理念

1. **单脚本即插即用（免交叉编译）：**  
   相比 `jni` 模式需要针对各操作系统与 CPU 架构分别编译 `.so` / `.dll`，JS 音源仅需单份 `.js` 文件即可在 Windows、macOS、Linux、Android（arm64/v7a/x86）上无差别运行。
2. **极简起步契约（与 Standard v3 扁平模型一致）：**  
   音源只需按需导出标准异步方法（`search`、`getMediaSource`、`getSongDetail`、`getLyric`），返回扁平数据模型。
3. **宿主原生算力注入（避免纯 JS 算力瓶颈）：**  
   宿主向 JS 沙箱注入原生高速能力，包括包装自 Ktor 的异步 `fetch`（自动处理 Cookie、Header 与重定向）、原生加解密 `crypto`（MD5、SHA256、AES、RSA）、独立持久化 `storage` 以及 Material 3 设置页参数映射 `env.settings`。

---

## 2. 插件包目录结构与打包规范

音源插件分发包为标准 `.zip` 压缩包（亦可重命名为 `.cpm`）：

```text
my-js-provider.zip (或 .cpm)
├── manifest.json       # 模块元数据定义（必须置于根目录）
├── index.js            # 入口脚本（对应 manifest 中的 entryPoint）
└── libs/               # 可选：附属脚本或工具库
    └── helper.js
```

### 2.1 清单文件定义 (`manifest.json`)

```json
{
  "id": "my-music-js",
  "name": "示例 JS 音源",
  "version": "1.0.0",
  "type": "js",
  "entryPoint": "index.js",
  "apiVersion": 1,
  "minHostApiVersion": 3,
  "capabilities": [
    "search",
    "playback",
    "lyricsSearch"
  ],
  "settingsSchema": [
    {
      "key": "cookie",
      "label": "用户 Cookie / SESSDATA",
      "type": "password",
      "placeholder": "请输入登录 Cookie 获取高清音质",
      "required": false
    }
  ]
}
```

- `type`: 固定声明为 `"js"`（兼容 `"script"`）；
- `entryPoint`: 入口脚本相对路径，如 `"index.js"`；
- `capabilities`: 声明插件支持的功能（宿主据此展示对应能力标签）。

---

## 3. 标准音源 API 契约

插件脚本可通过 `globalThis.CPPlayer = { ... }`、`export default { ... }` 或直接在全局定义契约函数。所有方法均应为 `async`（返回 `Promise`）：

### 3.1 歌曲与歌单搜索 (`search`)
- **入参对象：** `{ keywords, page, limit, type }`
- **返回值格式：**
```javascript
{
  code: 200,
  data: {
    songs: [
      {
        id: "1001",
        title: "歌曲名",
        artist: "歌手名",
        album: "专辑名",
        durationMs: 240000,
        coverUrl: "https://example.com/cover.jpg"
      }
    ],
    hasMore: true
  }
}
```

### 3.2 获取播放串流直链 (`getMediaSource`)
- **入参对象：** `{ id, quality }`（quality 常见取值：`"standard"`, `"exhigh"`, `"lossless"`, `"hires"`）
- **返回值格式：**
```javascript
{
  code: 200,
  url: "https://stream.example.com/audio.flac",
  headers: {
    "Referer": "https://music.example.com",
    "User-Agent": "Mozilla/5.0 ..."
  },
  format: "flac",
  bitrate: 320000
}
```

### 3.3 歌曲详情元数据 (`getSongDetail`)
- **入参对象：** `{ id }`
- **返回值格式：**
```javascript
{
  id: "1001",
  title: "歌曲名",
  artist: "歌手名",
  album: "专辑名",
  durationMs: 240000,
  coverUrl: "https://example.com/cover.jpg"
}
```

### 3.4 歌词获取 (`getLyric`)
- **入参对象：** `{ id }`
- **返回值格式：**
```javascript
{
  code: 200,
  lyric: "[00:00.00] 歌词正文...",
  tlyric: "[00:00.00] 翻译歌词（可选）...",
  format: "lrc" // 或 "ttml", "yrc"
}
```

---

## 4. 宿主沙箱注入的原生 API

为了让音源脚本能够高效完成网络逆向解析与数据抓取，宿主在执行脚本前已注入以下原生环境对象：

### 4.1 网络请求 (`fetch`)
```javascript
// 支持标准 async/await 调度
const res = await fetch("https://api.example.com/data", {
  method: "POST", // GET, POST, HEAD 等
  headers: {
    "Content-Type": "application/json",
    "Referer": "https://music.example.com"
  },
  body: JSON.stringify({ q: keywords })
});

const status = res.status; // 200
const ok = res.ok;         // true
const json = await res.json();
const text = await res.text();
```

### 4.2 原生加解密加速 (`crypto`)
避免在 JS 中进行低效的大数计算：
- `crypto.md5(text)`: 32 位小写 Hex 字符串；
- `crypto.sha1(text)`: 40 位小写 Hex 字符串；
- `crypto.sha256(text)`: 64 位小写 Hex 字符串；
- `crypto.base64Encode(text)`: 返回 Base64 编码；
- `crypto.base64Decode(base64)`: 返回 Base64 解码文本；
- `crypto.aesEncrypt(text, key, iv, { mode: "CBC", padding: "PKCS7Padding" })`: AES 加密（返回 Base64）；
- `crypto.aesDecrypt(cipherBase64, key, iv, { mode: "CBC", padding: "PKCS7Padding" })`: AES 解密（返回明文）；
- `crypto.rsaEncrypt(text, publicKeyPem)`: RSA 公钥加密。

### 4.3 本地独立键值持久化 (`storage`)
每个音源按 ID 拥有独立的持久化隔离存储：
- `storage.getItem(key)`: 获取字符串值（若不存在返回 null）；
- `storage.setItem(key, value)`: 保存字符串键值；
- `storage.removeItem(key)`: 删除指定键；
- `storage.clear()`: 清空当前音源的所有存储。

### 4.4 环境变量与配置项 (`env`)
```javascript
const id = env.id;             // 音源唯一标识
const version = env.version;   // 插件版本号
const userCookie = env.settings.cookie; // 用户在设置页中填写的参数
```

### 4.5 调试输出 (`console`)
`console.log(...)`、`console.info(...)`、`console.warn(...)`、`console.error(...)` 输出内容会自动注入至 CPPlayer 宿主日志管道。

---

## 5. 完整音源示例

```javascript
/**
 * 示例：轻量开放音频源插件 (index.js)
 */
globalThis.CPPlayer = {
  async search({ keywords, page, limit }) {
    console.log("搜索歌曲: " + keywords);
    const res = await fetch(`https://api.example.com/search?keyword=${encodeURIComponent(keywords)}&page=${page || 1}`);
    const data = await res.json();

    return {
      code: 200,
      data: {
        songs: (data.items || []).map(item => ({
          id: String(item.id),
          title: item.title,
          artist: item.artist,
          album: item.album || item.title,
          durationMs: item.duration * 1000,
          coverUrl: item.pic
        })),
        hasMore: data.hasMore || false
      }
    };
  },

  async getMediaSource({ id, quality }) {
    // 携带用户的设置项
    const token = env.settings.cookie || "";
    const sign = crypto.md5(id + token + "_secret");

    const res = await fetch(`https://api.example.com/song/url?id=${id}&sign=${sign}`);
    const data = await res.json();

    return {
      code: 200,
      url: data.url,
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer": "https://api.example.com/"
      }
    };
  },

  async getSongDetail({ id }) {
    const res = await fetch(`https://api.example.com/song/detail?id=${id}`);
    const item = await res.json();
    return {
      id: String(item.id),
      title: item.title,
      artist: item.artist,
      album: item.album,
      durationMs: item.duration * 1000,
      coverUrl: item.pic
    };
  }
};
```
