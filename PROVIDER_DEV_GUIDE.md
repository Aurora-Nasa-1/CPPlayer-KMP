# KMP-PRO Provider 开发指南

> **面向：** 为 KMP-PRO 开发音乐数据源（Provider）的第三方开发者
>
> **版本：** 3.0.0 · **最后更新：** 2026-07-16
>
> 本指南覆盖 KMP-PRO Provider 插件系统的完整 API 接口规范，
> 包含所有 API 方法的请求/响应格式、参数说明、字段要求和健康监控集成。

---

## 目录

1. [概述](#1-概述)
2. [快速开始](#2-快速开始)
3. [Provider 类型详解](#3-provider-类型详解)
4. [manifest.json 规范](#4-manifestjson-规范)
5. [API 协议规范](#5-api-协议规范)
6. [必选 API](#6-必选-api)
7. [可选 API 完整参考](#7-可选-api-完整参考)
    - [7.1 搜索](#71-搜索)
    - [7.2 认证](#72-认证)
    - [7.3 用户](#73-用户)
    - [7.4 歌单](#74-歌单)
    - [7.5 专辑](#75-专辑)
    - [7.6 歌手](#76-歌手)
    - [7.7 播放](#77-播放)
    - [7.8 MV](#78-mv)
    - [7.9 视频](#79-视频)
    - [7.10 电台 DJ](#710-电台-dj)
    - [7.11 社交（评论/私信）](#711-社交评论私信)
    - [7.12 排行榜 & 发现](#712-排行榜--发现)
    - [7.13 相似推荐](#713-相似推荐)
    - [7.14 签到](#714-签到)
    - [7.15 云盘扩展](#715-云盘扩展)
    - [7.16 其他](#716-其他)
8. [不支持的功能处理](#8-不支持的功能处理)
9. [健康监控与兼容性检查](#9-健康监控与兼容性检查)
10. [模块打包与分发](#10-模块打包与分发)
11. [调试与测试](#11-调试与测试)

---

## 1. 概述

KMP-PRO 通过 **Provider 插件系统**接入不同的音乐数据源。你只需实现一个能处理
标准化 API 请求的后端服务，编写 `manifest.json`，打包为 `.zip` 即可导入。

### 架构

```
KMP-PRO App
    │
    ├── MusicApiService ──→ ProviderManager ──→ 你的 Provider
    │                    (apiMap 映射)       (HTTP/Binary/JNI)
    │
    └── 标准化请求 ──→ 你的后端 ──→ 标准化 JSON 响应
```

### 关键概念

| 术语 | 说明 |
|------|------|
| **Provider** | 音乐数据源适配器，实现 `BackendProvider` 接口 |
| **apiMap** | CPPlayer 内部方法名 → Provider 实际端点的映射表 |
| **API 方法名** | 标准化标识，如 `song/url/v1`、`cloudsearch` |
| **CPMediaId** | 跨 Provider 统一资源标识，格式 `{providerId}://{type}/{id}` |

---

## 2. 快速开始

### 最简 HTTP Provider

```json
// manifest.json
{
    "id": "my-provider",
    "name": "My Music Provider",
    "version": "1.0.0",
    "type": "http",
    "entryPoint": "http://localhost:8080",
    "apiMap": {
        "cloudsearch": "search",
        "song/url/v1": "song/url",
        "song/url/v1/302": "song/url",
        "song/detail": "song/detail",
        "lyric/new": "lyric"
    }
}
```

打包：`zip -r my-provider.zip manifest.json`

导入：应用内 **设置 → 模块管理 → 导入模块**

---

## 3. Provider 类型详解

### 3.1 HTTP Provider

| 属性 | 说明 |
|------|------|
| `type` | `"http"` |
| `entryPoint` | API 基础 URL，如 `"http://localhost:3000"` |
| 通信方式 | HTTP POST `{entryPoint}/{mappedMethod}` |
| 启动方式 | 用户自行管理服务进程 |

**请求格式：**
```
POST {entryPoint}/{mappedMethod}
Content-Type: application/json

{"id": "123", "level": "standard"}
```

### 3.2 Binary Provider

| 属性 | 说明 |
|------|------|
| `type` | `"binary"` |
| `entryPoint` | 可执行文件名，如 `"ncm-server"` |
| 通信方式 | HTTP POST `http://127.0.0.1:{port}/api/{mappedMethod}` |
| 启动方式 | CPPlayer 自动启动 `./entryPoint --port {port}` |

**要求：**
- 可执行文件必须支持 `--port` 参数
- 必须实现 `POST /api/{method}` 和 `POST /{method}` 端点（兼容两种路径风格）
- 建议实现 `GET /health` 返回 `{"code":200,"status":"ok"}`

### 3.3 JNI Provider

| 属性 | 说明 |
|------|------|
| `type` | `"jni"` |
| `entryPoint` | Native 库文件名：`"libncm.so"`（Linux）、`"ncm.dll"`（Windows）、`"libncm.dylib"`（macOS） |

**必须导出的 JNI 函数：**

```c
// API 调用（核心）
JNIEXPORT jstring JNICALL
Java_cp_player_kmp_provider_JniProvider_nativeCallApi(
    JNIEnv *env, jobject obj, jstring method, jstring paramsJson);

// 启动本地服务（可选）
JNIEXPORT void JNICALL
Java_cp_player_kmp_provider_JniProvider_startNativeServer(
    JNIEnv *env, jobject obj, jstring host, jint port);

// 音频分析（可选）
JNIEXPORT jstring JNICALL
Java_cp_player_kmp_provider_JniProvider_analyzeAudioFile(
    JNIEnv *env, jobject obj, jstring path);
```

---

## 4. manifest.json 规范

```jsonc
{
    // === 必填字段 ===
    "id": "my-provider",            // 唯一标识，小写字母+连字符
    "name": "My Music Provider",    // 显示名称
    "version": "1.0.0",            // 语义化版本
    "type": "http",                // "http" | "binary" | "jni"
    "entryPoint": "http://...",    // URL、文件名或 .so 名

    // === 可选字段 ===
    "apiMap": {
        // CPPlayer 内部方法名 → Provider 端点映射
        // "unsupported" = 不支持该功能
        "cloudsearch": "search",
        "like": "unsupported"
    },
    "updateUrl": "https://example.com/update.json",
    "supportedAbis": ["arm64-v8a", "armeabi-v7a"],
    "targetAppPackage": "com.example.app"
}
```

**字段说明：**

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 是 | 唯一标识，小写字母 + 连字符 |
| `name` | 是 | 显示名称 |
| `version` | 是 | 语义化版本 |
| `type` | 是 | Provider 类型：`"http"` / `"binary"` / `"jni"` |
| `entryPoint` | 是 | URL、文件名或 .so 名 |
| `apiMap` | 否 | API 方法名映射表 |
| `updateUrl` | 否 | 检查更新 URL，指向返回最新版本信息的 JSON 端点 |
| `supportedAbis` | 否 | 支持的 CPU 架构列表，如 `["arm64-v8a", "armeabi-v7a"]` |
| `targetAppPackage` | 否 | 目标 App 的 **Android 包名**。用于登录页"打开目标 App"按钮，供用户一键跳转到音源对应的官方 App 扫码登录（如网易云音乐 `com.netease.cloudmusic`）。仅 Android 端生效。 |

### apiMap 详解

| 场景 | 示例 |
|------|------|
| 端点名不同 | `"cloudsearch": "search"` |
| 端点名相同 | 不写此条目，使用默认名称 |
| 不支持 | `"like": "unsupported"` |
| 多对一映射 | `"song/url/v1": "song/url", "song/url/v1/302": "song/url"` |

---

## 5. API 协议规范

### 5.1 请求

所有 API 调用使用 HTTP POST，Content-Type: `application/json`。

**参数说明：**
- 所有参数值均为字符串
- cookie 参数包含完整的登录凭据：`"MUSIC_U=xxx; __csrf=xxx; ..."`
- 列表参数用逗号分隔：`"ids": "123,456,789"`

### 5.2 响应

**必须返回 JSON 格式，至少包含 `code` 字段：**

```json
{
    "code": 200,
    "...": "业务数据字段"
}
```

**成功的 code 值：** `200`、`0`、`201`、`301`

**特别注意：** `login/qr/check` 返回 `code: 803` (扫码成功)、`802` (等待确认)、
`801` (等待扫码)、`800` (过期) — 这些是扫码状态码，CPPlayer 会按业务逻辑特殊处理。

**错误响应：**
```json
{
    "code": 500,
    "msg": "歌曲不存在"
}
```

### 5.3 音质等级 (level)

| 值 | 音质 | 码率 |
|----|------|------|
| `standard` | 标准 | ~128kbps |
| `higher` | 较高 | ~192kbps |
| `exhigh` | 极高 | ~320kbps |
| `lossless` | 无损 | FLAC |
| `hires` | Hi-Res | HiRes |
| `jymaster` | 超清母带 | - |
| `sky` | 沉浸环绕声 | - |
| `dolby` | 杜比全景声 | - |

### 5.4 资源类型码

| type 值 | 含义 | 使用场景 |
|---------|------|---------|
| `0` | 歌曲 | 评论、分享 |
| `1` | MV | 评论、分享 |
| `2` | 歌单 | 评论、分享 |
| `3` | 专辑 | 评论、分享 |
| `4` | 电台 | 评论、分享 |
| `5` | 视频 | 评论 |
| `6` | 动态 | 评论 |

---

## 6. 必选 API

**以下 3 个 API 是核心播放功能，必须实现：**

### `song/url/v1` — 歌曲播放 URL

| 项目 | 内容 |
|------|------|
| 方法名 | `song/url/v1` |
| 参数 | `id` (歌曲ID), `level` (音质), `cookie?` |
| 期望字段 | `data` → 数组，每项含 `url` |

**请求：**
```json
{"id": "123456", "level": "standard"}
```

**成功响应：**
```json
{
    "code": 200,
    "data": [
        {
            "id": 123456,
            "url": "https://m10.music.126.net/xxx.mp3",
            "br": 128000,
            "size": 3840000,
            "type": "mp3"
        }
    ]
}
```

**无版权：**
```json
{"code": 200, "data": [{"id": 123456, "url": null, "br": 0}]}
```

### `lyric/new` — 歌词

| 项目 | 内容 |
|------|------|
| 方法名 | `lyric/new` |
| 参数 | `id` (歌曲ID), `cookie?` |
| 期望字段 | `lrc` → `.lyric` 字段 |

**响应：**
```json
{
    "code": 200,
    "lrc": {
        "lyric": "[00:00.00]歌曲名 - 歌手\n[00:05.00]第一行歌词\n"
    },
    "yrc": {
        "lyric": "[00:00.00,0](0,500,0)逐(500,300,0)字\n"
    },
    "tlyric": {
        "lyric": "[00:05.00]翻译歌词\n"
    }
}
```

| 字段 | 说明 | 必须 |
|------|------|------|
| `lrc.lyric` | 行级 LRC 歌词 | ✅ |
| `yrc.lyric` | 逐字 YRC 歌词（AMLL TTML 格式） | 推荐 |
| `tlyric.lyric` | 翻译歌词 | 推荐 |

### `song/detail` — 歌曲详情

| 项目 | 内容 |
|------|------|
| 方法名 | `song/detail` |
| 参数 | `ids` (逗号分隔的歌曲ID) |
| 期望字段 | `songs` → 数组 |

**请求：**
```json
{"ids": "123456,789012"}
```

**响应：**
```json
{
    "code": 200,
    "songs": [
        {
            "id": 123456,
            "name": "歌曲名",
            "dt": 240000,
            "ar": [{"id": 111, "name": "歌手名"}],
            "al": {"id": 222, "name": "专辑名", "picUrl": "https://..."}
        }
    ]
}
```

---

## 7. 可选 API 完整参考

以下 API 解锁 CPPlayer 的更多功能。不支持的请在 `apiMap` 中标记为 `"unsupported"`。

---

### 7.1 搜索

#### `cloudsearch` — 云搜索

| 方法名 | `cloudsearch` |
|--------|---------------|
| 参数 | `keywords`, `type` (1=歌曲, 10=专辑, 100=歌手, 1000=歌单, 1014=视频) |
| 期望字段 | `result` |

**请求：**
```json
{"keywords": "周杰伦", "type": "1"}
```

**响应（type=1）：**
```json
{
    "code": 200,
    "result": {
        "songs": [
            {
                "id": 123, "name": "晴天",
                "ar": [{"id": 1, "name": "周杰伦"}],
                "al": {"id": 1, "name": "叶惠美", "picUrl": "https://..."},
                "dt": 269000
            }
        ],
        "songCount": 100
    }
}
```

**响应（type=1000）：**
```json
{
    "code": 200,
    "result": {
        "playlists": [
            {
                "id": 456, "name": "周杰伦精选",
                "coverImgUrl": "https://...",
                "trackCount": 50,
                "creator": {"nickname": "User"}
            }
        ],
        "playlistCount": 200
    }
}
```

#### `search/hot/detail` — 热搜

| 方法名 | `search/hot/detail` |
|--------|---------------------|
| 参数 | 无 |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"searchWord": "热搜词", "content": "描述", "score": 10000}
    ]
}
```

#### `search/suggest` — 搜索建议

| 方法名 | `search/suggest` |
|--------|------------------|
| 参数 | `keywords`, `type` |

**响应：**
```json
{
    "code": 200,
    "result": {
        "allMatch": [{"keyword": "周杰伦"}, {"keyword": "周杰伦晴天"}]
    }
}
```

---

### 7.2 认证

#### `login/qr/key` — 获取扫码 key

| 方法名 | `login/qr/key` |
|--------|---------------|
| 参数 | 无 |

**响应：**
```json
{"code": 200, "data": {"unikey": "xxxxxxxxxxxx"}}
```

#### `login/qr/create` — 创建二维码

| 方法名 | `login/qr/create` |
|--------|------------------|
| 参数 | `key`, `qrimg` ("true") |

**响应：**
```json
{
    "code": 200,
    "data": {
        "qrimg": "data:image/png;base64,iVBORw0KGgo...",
        "qrurl": "https://music.163.com/login?codekey=xxx"
    }
}
```

#### `login/qr/check` — 检查扫码状态

| 方法名 | `login/qr/check` |
|--------|------------------|
| 参数 | `key` |

**响应（code 含义）：**

| code | 状态 |
|------|------|
| 800 | 二维码过期 |
| 801 | 等待扫码 |
| 802 | 等待确认 |
| 803 | 登录成功（含 cookie） |

```json
{"code": 803, "cookie": "MUSIC_U=xxx; __csrf=xxx"}
```

#### `login` — 邮箱登录

| 方法名 | `login` |
|--------|---------|
| 参数 | `email`, `password` 或 `md5_password` |

**响应：**
```json
{"code": 200, "cookie": "MUSIC_U=xxx; __csrf=xxx"}
```

#### `login/cellphone` — 手机登录

| 方法名 | `login/cellphone` |
|--------|-------------------|
| 参数 | `phone`, `password` 或 `captcha` 或 `md5_password` |

**响应：** 同 `login`

#### `captcha/sent` — 发送验证码

| 方法名 | `captcha/sent` |
|--------|----------------|
| 参数 | `phone` |

**响应：**
```json
{"code": 200}
```

#### `logout` — 登出

| 方法名 | `logout` |
|--------|----------|
| 参数 | 无 |

**响应：**
```json
{"code": 200}
```

#### `register/anonimous` — 游客登录

| 方法名 | `register/anonimous` |
|--------|---------------------|
| 参数 | 无 |

**响应：**
```json
{"code": 200, "cookie": "xxx"}
```

#### `login/status` — 登录状态查询

| 方法名 | `login/status` |
|--------|----------------|
| 参数 | `cookie?` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "profile": {
            "userId": 123456,
            "nickname": "User",
            "avatarUrl": "https://...",
            "follows": 100,
            "followeds": 200
        }
    }
}
```

---

### 7.3 用户

#### `user/playlist` — 用户全部歌单

| 方法名 | `user/playlist` |
|--------|-----------------|
| 参数 | `uid`, `cookie` |
| 期望字段 | `playlist` |

**响应：**
```json
{
    "code": 200,
    "playlist": [
        {
            "id": 789, "name": "我喜欢的音乐",
            "coverImgUrl": "https://...", "trackCount": 50,
            "subscribed": false,
            "creator": {"nickname": "User", "userId": 123456}
        }
    ]
}
```

#### `user/playlist/create` — 创建的歌单

| 方法名 | `user/playlist/create` |
|--------|------------------------|
| 参数 | `uid`, `cookie` |
| 期望字段 | `playlist` |

**响应：** 同 `user/playlist`，但只返回用户自己的歌单

#### `user/playlist/collect` — 收藏的歌单

| 方法名 | `user/playlist/collect` |
|--------|-------------------------|
| 参数 | `uid`, `cookie` |
| 期望字段 | `playlist` |

**响应：** 同 `user/playlist`，只返回收藏的歌单（`subscribed: true`）

#### `user/detail` — 用户详情

| 方法名 | `user/detail` |
|--------|---------------|
| 参数 | `uid` |
| 期望字段 | `profile` |

**响应：**
```json
{
    "code": 200,
    "profile": {
        "userId": 123456, "nickname": "User",
        "avatarUrl": "https://...", "signature": "...",
        "gender": 1, "birthday": 946656000000,
        "follows": 100, "followeds": 200,
        "playlistCount": 15
    }
}
```

#### `user/cloud` — 云盘歌曲

| 方法名 | `user/cloud` |
|--------|--------------|
| 参数 | `limit`, `offset`, `cookie` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "songId": "123", "songName": "My Upload",
            "artist": "Me", "album": "My Album",
            "simpleSong": {
                "id": 123, "name": "歌曲名",
                "ar": [{"id": 1, "name": "歌手"}],
                "al": {"id": 1, "name": "专辑"}
            },
            "fileSize": 10000000
        }
    ]
}
```

#### `likelist` — 喜欢的歌曲 ID 列表

| 方法名 | `likelist` |
|--------|------------|
| 参数 | `uid`, `cookie` |
| 期望字段 | `ids` |

**响应：**
```json
{"code": 200, "ids": ["123", "456", "789"]}
```

#### `like` — 喜欢/取消喜欢

| 方法名 | `like` |
|--------|--------|
| 参数 | `id` (歌曲ID), `like` ("true"/"false"), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `recommend/songs` — 每日推荐

| 方法名 | `recommend/songs` |
|--------|-------------------|
| 参数 | `cookie` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "dailySongs": [
            {
                "id": 123, "name": "推荐歌曲",
                "ar": [{"id": 11, "name": "歌手"}],
                "al": {"id": 22, "name": "专辑", "picUrl": "https://..."},
                "dt": 240000
            }
        ]
    }
}
```

#### `recommend/resource` — 推荐歌单

| 方法名 | `recommend/resource` |
|--------|----------------------|
| 参数 | `cookie` |
| 期望字段 | `recommend` |

**响应：**
```json
{
    "code": 200,
    "recommend": [
        {
            "id": 789, "name": "推荐歌单",
            "picUrl": "https://...", "trackCount": 30,
            "creator": {"nickname": "..."}
        }
    ]
}
```

#### `recommend/songs/dislike` — 不喜欢推荐

| 方法名 | `recommend/songs/dislike` |
|--------|---------------------------|
| 参数 | `id` (歌曲ID), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `user/record` — 听歌排行

| 方法名 | `user/record` |
|--------|---------------|
| 参数 | `uid`, `type` (0=所有时间, 1=最近一周) |
| 期望字段 | `allData` |

**响应：**
```json
{
    "code": 200,
    "allData": [
        {
            "playCount": 42,
            "score": 100,
            "song": {
                "id": 123, "name": "常听的歌",
                "ar": [{"id": 1, "name": "歌手"}],
                "al": {"id": 1, "name": "专辑", "picUrl": "https://..."}
            }
        }
    ],
    "weekData": [...]
}
```

#### `user/follows` — 关注列表

| 方法名 | `user/follows` |
|--------|----------------|
| 参数 | `uid`, `limit`, `offset` |
| 期望字段 | `follow` |

**响应：**
```json
{
    "code": 200,
    "follow": [
        {
            "userId": 111, "nickname": "User",
            "avatarUrl": "https://...", "signature": "...",
            "gender": 1
        }
    ]
}
```

#### `user/followeds` — 粉丝列表

| 方法名 | `user/followeds` |
|--------|------------------|
| 参数 | `uid`, `limit`, `offset` |
| 期望字段 | `followeds` |

**响应：**
```json
{
    "code": 200,
    "followeds": [
        {"userId": 222, "nickname": "Follower", "avatarUrl": "https://..."}
    ]
}
```

#### `user/event` — 用户动态

| 方法名 | `user/event` |
|--------|--------------|
| 参数 | `uid`, `limit` |
| 期望字段 | `events` |

**响应：**
```json
{
    "code": 200,
    "events": [
        {
            "id": 12345,
            "user": {"userId": 111, "nickname": "User"},
            "json": "{\"msg\":\"分享了一首歌曲\"}",
            "eventTime": 1687000000000
        }
    ]
}
```

#### `user/update` — 更新用户信息

| 方法名 | `user/update` |
|--------|---------------|
| 参数 | `nickname?`, `signature?`, `gender?` (0=保密,1=男,2=女), `birthday?` (时间戳), `province?`, `city?` |

**响应：**
```json
{"code": 200}
```

#### `user/account` — 账号信息

| 方法名 | `user/account` |
|--------|----------------|
| 参数 | 无 |
| 期望字段 | `profile` |

**响应：**
```json
{
    "code": 200,
    "profile": {
        "userId": 123456, "nickname": "User",
        "avatarUrl": "https://...",
        "vipType": 2, "createTime": 1500000000000,
        "birthday": 946656000000
    }
}
```

#### `user/dj` — 用户电台

| 方法名 | `user/dj` |
|--------|-----------|
| 参数 | `uid` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "id": "12345", "name": "我的电台",
            "programCount": 10,
            "dj": {"userId": 111, "nickname": "DJ"}
        }
    ]
}
```

---

### 7.4 歌单

#### `playlist/detail` — 歌单详情

| 方法名 | `playlist/detail` |
|--------|-------------------|
| 参数 | `id` |
| 期望字段 | `playlist` |

**响应：**
```json
{
    "code": 200,
    "playlist": {
        "id": 789, "name": "歌单名",
        "coverImgUrl": "https://...", "trackCount": 50,
        "creator": {"nickname": "User", "userId": 111},
        "description": "...",
        "subscribed": false, "playCount": 10000
    }
}
```

#### `playlist/track/all` — 全部曲目

| 方法名 | `playlist/track/all` |
|--------|----------------------|
| 参数 | `id`, `limit`, `offset` |
| 期望字段 | `songs` |

**响应：**
```json
{
    "code": 200,
    "songs": [
        {
            "id": 123, "name": "歌曲",
            "ar": [{"id": 1, "name": "歌手"}],
            "al": {"id": 1, "name": "专辑", "picUrl": "https://..."},
            "dt": 240000
        }
    ]
}
```

#### `playlist/tracks` — 添加/删除曲目

| 方法名 | `playlist/tracks` |
|--------|-------------------|
| 参数 | `op` ("add"/"del"), `pid`, `tracks` (逗号分隔ID), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/create` — 创建歌单

| 方法名 | `playlist/create` |
|--------|-------------------|
| 参数 | `name`, `privacy` (0=公开, 10=私密), `cookie` |

**响应：**
```json
{"code": 200, "playlist": {"id": 999, "name": "新歌单"}}
```

#### `playlist/delete` — 删除歌单

| 方法名 | `playlist/delete` |
|--------|-------------------|
| 参数 | `id`, `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/subscribe` — 收藏/取消收藏

| 方法名 | `playlist/subscribe` |
|--------|----------------------|
| 参数 | `id`, `t` ("1"=收藏, "2"=取消收藏), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/catlist` — 歌单分类

| 方法名 | `playlist/catlist` |
|--------|-------------------|
| 参数 | 无 |
| 期望字段 | `categories` |

**响应：**
```json
{
    "code": 200,
    "categories": {
        "0": "语种", "1": "风格", "2": "场景", "3": "情感", "4": "主题"
    },
    "sub": [
        {"name": "华语", "category": 0},
        {"name": "流行", "category": 1}
    ]
}
```

#### `playlist/hot` — 热门歌单标签

| 方法名 | `playlist/hot` |
|--------|----------------|
| 参数 | 无 |
| 期望字段 | `tags` |

**响应：**
```json
{
    "code": 200,
    "tags": [
        {"id": 1, "name": "华语", "category": 0, "hot": true}
    ]
}
```

#### `playlist/update` — 更新歌单

| 方法名 | `playlist/update` |
|--------|-------------------|
| 参数 | `id`, `name?`, `desc?`, `tags?` (逗号分隔), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/subscribers` — 收藏者列表

| 方法名 | `playlist/subscribers` |
|--------|------------------------|
| 参数 | `id`, `limit`, `offset` |
| 期望字段 | `subscribers` |

**响应：**
```json
{
    "code": 200,
    "subscribers": [
        {"userId": 111, "nickname": "User", "avatarUrl": "https://..."}
    ]
}
```

#### `playlist/desc/update` — 更新描述

| 方法名 | `playlist/desc/update` |
|--------|------------------------|
| 参数 | `id`, `desc`, `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/name/update` — 更新名称

| 方法名 | `playlist/name/update` |
|--------|------------------------|
| 参数 | `id`, `name`, `cookie` |

**响应：**
```json
{"code": 200}
```

#### `playlist/highquality/tags` — 精品歌单标签

| 方法名 | `playlist/highquality/tags` |
|--------|----------------------------|
| 参数 | 无 |
| 期望字段 | `tags` |

**响应：**
```json
{
    "code": 200,
    "tags": [
        {"name": "流行", "category": 1, "hot": true}
    ]
}
```

---

### 7.5 专辑

#### `album` — 专辑详情

| 方法名 | `album` |
|--------|---------|
| 参数 | `id` |
| 期望字段 | `album` |

**响应：**
```json
{
    "code": 200,
    "album": {
        "id": 222, "name": "专辑名", "picUrl": "https://...",
        "artist": {"id": 111, "name": "歌手名"},
        "description": "简介", "publishTime": 1687000000000
    },
    "songs": [
        {
            "id": 123, "name": "歌曲",
            "ar": [{"id": 111, "name": "歌手"}],
            "al": {"id": 222, "name": "专辑", "picUrl": "https://..."},
            "dt": 240000
        }
    ]
}
```

#### `album/list` — 数字专辑

| 方法名 | `album/list` |
|--------|--------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `products` |

**响应：**
```json
{
    "code": 200,
    "products": [
        {"albumId": 222, "albumName": "数字专辑", "picUrl": "https://...", "price": 20}
    ]
}
```

#### `album/new` — 最新专辑

| 方法名 | `album/new` |
|--------|-------------|
| 参数 | 无 |
| 期望字段 | `albums` |

**响应：**
```json
{
    "code": 200,
    "albums": [
        {"id": 222, "name": "新专辑", "picUrl": "https://...", "artist": {"name": "歌手"}}
    ]
}
```

#### `album/newest` — 最新专辑（分页）

| 方法名 | `album/newest` |
|--------|----------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `albums` |

**响应：** 同 `album/new`

#### `album/sub` — 收藏/取消收藏专辑

| 方法名 | `album/sub` |
|--------|-------------|
| 参数 | `id`, `t` ("1"=收藏, "2"=取消收藏), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `album/sublist` — 已收藏专辑

| 方法名 | `album/sublist` |
|--------|-----------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"id": 222, "name": "专辑名", "picUrl": "https://...", "artist": {"name": "歌手"}}
    ]
}
```

---

### 7.6 歌手

#### `artist/detail` — 歌手详情

| 方法名 | `artist/detail` |
|--------|-----------------|
| 参数 | `id` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "artist": {
            "id": 111, "name": "歌手名",
            "cover": "https://...", "picUrl": "https://...",
            "briefDesc": "简介...", "albumSize": 10, "musicSize": 50
        },
        "user": {"followeds": 5000}
    }
}
```

#### `artist/songs` — 歌手歌曲

| 方法名 | `artist/songs` |
|--------|----------------|
| 参数 | `id`, `limit` |
| 期望字段 | `songs` |

**响应：**
```json
{
    "code": 200,
    "songs": [
        {
            "id": 123, "name": "歌曲",
            "ar": [{"id": 111, "name": "歌手"}],
            "al": {"id": 1, "name": "专辑", "picUrl": "https://..."},
            "dt": 240000
        }
    ]
}
```

#### `artist/album` — 歌手专辑

| 方法名 | `artist/album` |
|--------|----------------|
| 参数 | `id`, `limit` |
| 期望字段 | `hotAlbums` |

**响应：**
```json
{
    "code": 200,
    "hotAlbums": [
        {"id": 222, "name": "专辑名", "picUrl": "https://...", "size": 10}
    ]
}
```

#### `artist/top/song` — 歌手热门 50 首

| 方法名 | `artist/top/song` |
|--------|-------------------|
| 参数 | `id` |
| 期望字段 | `songs` |

**响应：** 同 `artist/songs`，默认返回最热门的 50 首

#### `artist/sub` — 收藏/取消收藏歌手

| 方法名 | `artist/sub` |
|--------|--------------|
| 参数 | `id`, `t` ("1"=收藏, "2"=取消收藏), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `artist/sublist` — 已收藏歌手

| 方法名 | `artist/sublist` |
|--------|------------------|
| 参数 | 无 |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"id": 111, "name": "歌手名", "picUrl": "https://...", "albumSize": 10}
    ]
}
```

#### `artist/mv` — 歌手 MV

| 方法名 | `artist/mv` |
|--------|-------------|
| 参数 | `id`, `limit`, `offset` |
| 期望字段 | `mvs` |

**响应：**
```json
{
    "code": 200,
    "mvs": [
        {
            "id": 12345, "name": "MV名",
            "cover": "https://...", "playCount": 10000,
            "duration": 240000, "artists": [{"id": 1, "name": "歌手"}]
        }
    ]
}
```

#### `artist/list` — 歌手分类列表

| 方法名 | `artist/list` |
|--------|---------------|
| 参数 | `type` (-1=全部,1=男,2=女,3=乐队), `area` (-100=全部,7=华语,96=欧美,8=日本,16=韩国), `initial` (首字母), `limit`, `offset` |
| 期望字段 | `artists` |

**响应：**
```json
{
    "code": 200,
    "artists": [
        {"id": 111, "name": "歌手名", "picUrl": "https://...", "albumSize": 10}
    ]
}
```

#### `artist/follow/count` — 歌手粉丝数

| 方法名 | `artist/follow/count` |
|--------|-----------------------|
| 参数 | `id` |

**响应：**
```json
{"code": 200, "data": {"followCount": 50000}}
```

---

### 7.7 播放

#### `song/url/v1/302` — 302 重定向播放 URL

| 方法名 | `song/url/v1/302` |
|--------|-------------------|
| 参数 | 同 `song/url/v1` |
| 期望字段 | `data` |

**说明：** CPPlayer 优先使用此端点。若返回无效 URL，自动回退到 `song/url/v1`。
建议在 `apiMap` 中将两者映射到同一端点：
```json
"apiMap": {
    "song/url/v1": "song/url",
    "song/url/v1/302": "song/url"
}
```

#### `song/download/url/v1` — 下载 URL

| 方法名 | `song/download/url/v1` |
|--------|------------------------|
| 参数 | `id`, `level`, `cookie?` |
| 期望字段 | `data` |

**响应：** 同 `song/url/v1`

#### `scrobble` — 听歌打卡

| 方法名 | `scrobble` |
|--------|------------|
| 参数 | `id` (歌曲ID), `sourceid` (来源歌单ID), `time` (播放秒数), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `personal_fm` — 私人 FM

| 方法名 | `personal_fm` |
|--------|---------------|
| 参数 | `timestamp`, `cookie` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "id": 123, "name": "FM 歌曲",
            "ar": [{"id": 1, "name": "歌手"}],
            "al": {"id": 1, "name": "专辑", "picUrl": "https://..."},
            "dt": 240000
        }
    ]
}
```

#### `playmode/intelligence/list` — 心动模式

| 方法名 | `playmode/intelligence/list` |
|--------|------------------------------|
| 参数 | `id` (当前歌曲ID), `pid` (歌单ID), `sid` (歌曲ID, 同id), `count`, `cookie` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"id": 456, "name": "...", "ar": [...], "al": {...}}
    ]
}
```

---

### 7.8 MV

#### `mv/detail` — MV 详情

| 方法名 | `mv/detail` |
|--------|-------------|
| 参数 | `mvid` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "id": 12345, "name": "MV名",
        "cover": "https://...", "desc": "描述",
        "playCount": 10000, "duration": 240000,
        "publishTime": "2023-01-01",
        "artists": [{"id": 1, "name": "歌手"}]
    }
}
```

#### `mv/url` — MV 播放地址

| 方法名 | `mv/url` |
|--------|----------|
| 参数 | `id`, `r` (分辨率: 240/480/720/1080) |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "id": 12345,
        "url": "https://v.163.com/xxx.mp4",
        "r": 1080, "size": 50000000
    }
}
```

#### `mv/all` — 全部 MV

| 方法名 | `mv/all` |
|--------|----------|
| 参数 | `area` (地区: 全部/内地/港台/欧美/日本/韩国), `limit`, `offset` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "id": 12345, "name": "MV名",
            "cover": "https://...", "playCount": 10000,
            "duration": 240000, "artistName": "歌手"
        }
    ]
}
```

#### `mv/first` — 最新 MV

| 方法名 | `mv/first` |
|--------|------------|
| 参数 | `limit` |
| 期望字段 | `data` |

**响应：** 格式同 `mv/all`

#### `mv/sub` — 收藏/取消收藏 MV

| 方法名 | `mv/sub` |
|--------|----------|
| 参数 | `mvid`, `t` ("1"=收藏, "2"=取消收藏), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `mv/sublist` — 已收藏 MV

| 方法名 | `mv/sublist` |
|--------|--------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"id": 12345, "cover": "https://...", "name": "MV名", "artistName": "歌手"}
    ]
}
```

---

### 7.9 视频

#### `video/detail` — 视频详情

| 方法名 | `video/detail` |
|--------|----------------|
| 参数 | `id` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "vid": "12345", "title": "视频标题",
        "coverUrl": "https://...", "description": "描述",
        "playTime": 120000, "publishTime": 1687000000000,
        "creator": {"userId": 111, "nickname": "创作者"}
    }
}
```

#### `video/url` — 视频播放地址

| 方法名 | `video/url` |
|--------|-------------|
| 参数 | `id`, `res` (分辨率: 240/480/720/1080) |
| 期望字段 | `urls` |

**响应：**
```json
{
    "code": 200,
    "urls": [
        {"id": 12345, "url": "https://v.163.com/xxx.mp4", "r": 1080, "size": 50000000}
    ]
}
```

#### `video/group` — 视频分组

| 方法名 | `video/group` |
|--------|---------------|
| 参数 | 无 |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {"id": 1, "name": "推荐", "type": 0}
    ]
}
```

#### `video/timeline/all` — 视频时间线

| 方法名 | `video/timeline/all` |
|--------|----------------------|
| 参数 | `offset` |
| 期望字段 | `datas` |

**响应：**
```json
{
    "code": 200,
    "datas": [
        {
            "id": 12345, "title": "视频标题",
            "coverUrl": "https://...", "playTime": 120000,
            "creator": {"userId": 1, "nickname": "作者"}
        }
    ]
}
```

---

### 7.10 电台 DJ

#### `dj/detail` — 电台详情

| 方法名 | `dj/detail` |
|--------|-------------|
| 参数 | `rid` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "id": 123, "name": "电台名",
        "picUrl": "https://...", "desc": "描述",
        "subCount": 10000, "programCount": 50,
        "dj": {"userId": 111, "nickname": "DJ", "avatarUrl": "https://..."}
    }
}
```

#### `dj/program` — 电台节目列表

| 方法名 | `dj/program` |
|--------|--------------|
| 参数 | `rid` (电台ID), `limit`, `offset`, `asc` (true=正序, false=倒序) |
| 期望字段 | `programs` |

**响应：**
```json
{
    "code": 200,
    "programs": [
        {
            "id": 1001, "name": "节目名",
            "coverUrl": "https://...", "duration": 3600000,
            "listenerCount": 5000, "likedCount": 1000,
            "createTime": 1687000000000,
            "radio": {"id": 123, "name": "电台名"}
        }
    ]
}
```

#### `dj/hot` — 热门电台

| 方法名 | `dj/hot` |
|--------|----------|
| 参数 | `limit`, `offset` |
| 期望字段 | `djRadios` |

**响应：**
```json
{
    "code": 200,
    "djRadios": [
        {
            "id": 123, "name": "热门电台",
            "picUrl": "https://...", "subCount": 10000,
            "programCount": 50,
            "dj": {"userId": 111, "nickname": "DJ"}
        }
    ]
}
```

#### `dj/toplist` — 电台排行榜

| 方法名 | `dj/toplist` |
|--------|--------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `toplist` |

**响应：**
```json
{
    "code": 200,
    "toplist": [
        {
            "id": 123, "name": "榜单电台",
            "picUrl": "https://...", "score": 10000,
            "dj": {"nickname": "DJ"}
        }
    ]
}
```

#### `dj/recommend` — 推荐电台

| 方法名 | `dj/recommend` |
|--------|----------------|
| 参数 | 无 |
| 期望字段 | `djRadios` |

**响应：** 格式同 `dj/hot`

#### `dj/sub` — 收藏/取消收藏电台

| 方法名 | `dj/sub` |
|--------|----------|
| 参数 | `rid`, `t` ("1"=收藏, "0"=取消收藏), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `dj/sublist` — 已收藏电台

| 方法名 | `dj/sublist` |
|--------|--------------|
| 参数 | 无 |
| 期望字段 | `djRadios` |

**响应：** 格式同 `dj/hot`

#### `program/recommend` — 推荐节目

| 方法名 | `program/recommend` |
|--------|---------------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `programs` |

**响应：** 格式同 `dj/program` 中的 `programs`

---

### 7.11 社交（评论/私信）

#### 评论类 API 通用

**支持 6 种评论类型：**

| 方法名 | 资源类型 |
|--------|---------|
| `comment/music` | 歌曲 |
| `comment/playlist` | 歌单 |
| `comment/album` | 专辑 |
| `comment/mv` | MV |
| `comment/dj` | 电台 |
| `comment/video` | 视频 |

**通用请求参数：**

| 参数 | 说明 |
|------|------|
| `id` | 资源 ID |
| `limit` | 每页数量，默认 20 |
| `offset` | 偏移量（页码 = offset/limit） |
| `sortType` | `0`=推荐排序，`1`=时间排序，`2`=热度排序 |

**响应：**
```json
{
    "code": 200,
    "comments": [
        {
            "commentId": 789,
            "user": {"userId": 111, "nickname": "User", "avatarUrl": "https://..."},
            "content": "评论内容",
            "time": 1687000000000,
            "timeStr": "2023-06-17",
            "likedCount": 42, "liked": false, "replyCount": 3,
            "beReplied": [
                {
                    "user": {"userId": 222, "nickname": "Other"},
                    "content": "被回复的内容"
                }
            ]
        }
    ],
    "hotComments": [
        {"commentId": 100, "user": {...}, "content": "热评", "likedCount": 999}
    ],
    "totalCount": 100, "hasMore": true
}
```

#### `comment/floor` — 楼层评论

| 方法名 | `comment/floor` |
|--------|-----------------|
| 参数 | `id`, `parentCommentId`, `type` (0-6 资源类型码), `limit`, `time?` |
| 期望字段 | `data` (内含 `comments`) |

#### `comment/like` — 点赞评论

| 方法名 | `comment/like` |
|--------|----------------|
| 参数 | `id` (资源ID), `cid` (评论ID), `t` ("1"=赞, "0"=取消), `type` (资源类型码 0-6), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `comment` — 发表/回复评论

| 方法名 | `comment` |
|--------|-----------|
| 参数 | `id` (资源ID), `type` (资源类型码), `content`, `op` ("add"/"reply"), `commentId?` (回复时必填), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `pl/count` — 未读消息数

| 方法名 | `pl/count` |
|--------|------------|
| 参数 | `cookie` |

**响应：**
```json
{"code": 200, "msg": 5}
```

> `msg` 字段为未读数量（整数）

#### `msg/recentcontact` — 最近联系人

| 方法名 | `msg/recentcontact` |
|--------|---------------------|
| 参数 | `cookie` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": [
        {
            "fromUser": {"userId": 111, "nickname": "User", "avatarUrl": "https://..."},
            "lastMsg": "{\"msg\":\"hello\"}",
            "lastMsgTime": 1687000000000, "newMsgCount": 2
        }
    ]
}
```

#### `msg/private` — 私信列表

| 方法名 | `msg/private` |
|--------|---------------|
| 参数 | `limit`, `cookie` |

#### `msg/private/history` — 私信历史

| 方法名 | `msg/private/history` |
|--------|-----------------------|
| 参数 | `uid`, `cookie` |

#### `msg/private/mark/read` — 标记已读

| 方法名 | `msg/private/mark/read` |
|--------|--------------------------|
| 参数 | `uid`, `cookie` |

#### `send/text` — 发送消息

| 方法名 | `send/text` |
|--------|-------------|
| 参数 | `user_ids` (逗号分隔), `msg` (消息内容), `cookie` |

---

### 7.12 排行榜 & 发现

#### `toplist` — 所有榜单

| 方法名 | `toplist` |
|--------|-----------|
| 参数 | 无 |
| 期望字段 | `list` |

**响应：**
```json
{
    "code": 200,
    "list": [
        {
            "id": 19723756, "name": "飙升榜",
            "coverImgUrl": "https://...", "updateFrequency": "每天更新"
        }
    ]
}
```

#### `toplist_detail` — 榜单内容摘要

| 方法名 | `toplist_detail` |
|--------|------------------|
| 参数 | 无 |
| 期望字段 | `list` |

**响应：**
```json
{
    "code": 200,
    "list": [
        {
            "id": 19723756, "name": "飙升榜",
            "coverImgUrl": "https://...",
            "tracks": [
                {"first": "歌曲名", "second": "歌手名"}
            ]
        }
    ]
}
```

#### `top_song` — 新歌速递

| 方法名 | `top_song` |
|--------|------------|
| 参数 | `type` (0=全部, 7=华语, 96=欧美, 8=日本, 16=韩国) |
| 期望字段 | `data` |

#### `top_album` — 新碟上架

| 方法名 | `top_album` |
|--------|-------------|
| 参数 | `area` (ALL/ZH/EA/KR/JP), `limit`, `offset` |
| 期望字段 | `albums` |

#### `top_artists` — 热门歌手

| 方法名 | `top_artists` |
|--------|---------------|
| 参数 | `limit`, `offset` |
| 期望字段 | `artists` |

#### `top_playlist` — 热门歌单

| 方法名 | `top_playlist` |
|--------|----------------|
| 参数 | `order` (new/hot), `cat` (分类标签), `limit`, `offset` |
| 期望字段 | `playlists` |

#### `top_playlist_highquality` — 精品歌单

| 方法名 | `top_playlist_highquality` |
|--------|----------------------------|
| 参数 | `cat`, `limit`, `before` (翻页用) |
| 期望字段 | `playlists` |

#### `personalized` — 推荐歌单（无需登录）

| 方法名 | `personalized` |
|--------|----------------|
| 参数 | `limit` |
| 期望字段 | `result` |

#### `personalized_newsong` — 推荐新音乐

| 方法名 | `personalized_newsong` |
|--------|------------------------|
| 参数 | `limit` |
| 期望字段 | `result` |

#### `banner` — 首页 Banner

| 方法名 | `banner` |
|--------|----------|
| 参数 | `type` (0=pc, 1=android, 2=iphone, 3=ipad) |
| 期望字段 | `banners` |

**响应：**
```json
{
    "code": 200,
    "banners": [
        {
            "pic": "https://...", "targetId": 123,
            "typeTitle": "新歌推荐", "url": "https://..."
        }
    ]
}
```

#### `history_recommend_songs` — 历史日推可用日期

| 方法名 | `history_recommend_songs` |
|--------|---------------------------|
| 参数 | `cookie` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "dates": ["2023-06-17", "2023-06-16"]
    }
}
```

#### `history_recommend_songs_detail` — 历史日推详情

| 方法名 | `history_recommend_songs_detail` |
|--------|----------------------------------|
| 参数 | `date` (如 "2023-06-17"), `cookie` |

---

### 7.13 相似推荐

#### `simi_song` — 相似歌曲

| 方法名 | `simi_song` |
|--------|-------------|
| 参数 | `id` |
| 期望字段 | `songs` |

#### `simi_artist` — 相似歌手

| 方法名 | `simi_artist` |
|--------|---------------|
| 参数 | `id` |
| 期望字段 | `artists` |

#### `simi_playlist` — 相似歌单

| 方法名 | `simi_playlist` |
|--------|-----------------|
| 参数 | `id` |
| 期望字段 | `playlists` |

---

### 7.14 签到

#### `daily/signin` — 每日签到

| 方法名 | `daily/signin` |
|--------|----------------|
| 参数 | `type` (0=安卓, 1=web), `cookie` |

**响应：**
```json
{
    "code": 200,
    "point": 5, "code": 200
}
```

---

### 7.15 云盘扩展

#### `user/cloud/del` — 删除云盘歌曲

| 方法名 | `user/cloud/del` |
|--------|------------------|
| 参数 | `id` (逗号分隔的歌曲ID), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `cloud/import` — 导入云盘

| 方法名 | `cloud/import` |
|--------|----------------|
| 参数 | `songId` (云盘歌曲ID), `matchSongId` (匹配的线上歌曲ID) |

#### `cloud/match` — 云盘匹配

| 方法名 | `cloud/match` |
|--------|---------------|
| 参数 | `uid`, `songId`, `adjustSongId` |

---

### 7.16 其他

#### `check/music` — 检查歌曲可用性

| 方法名 | `check/music` |
|--------|---------------|
| 参数 | `id`, `br` (码率，默认 999000) |

**响应：**
```json
{
    "code": 200,
    "success": true, "message": "ok"
}
```

#### `record/recent/song` — 最近播放

| 方法名 | `record/recent/song` |
|--------|----------------------|
| 参数 | `limit` |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "list": [
            {
                "data": {
                    "id": 123, "name": "播放过的歌",
                    "ar": [{"id": 1, "name": "歌手"}],
                    "al": {"id": 1, "name": "专辑"}
                },
                "playTime": 1687000000000
            }
        ]
    }
}
```

#### `calendar` — 音乐日历

| 方法名 | `calendar` |
|--------|------------|
| 参数 | 无 |
| 期望字段 | `data` |

**响应：**
```json
{
    "code": 200,
    "data": {
        "calendar": [
            {"id": 12345, "date": "2023-01-01", "content": "专辑发行"}
        ]
    }
}
```

#### `event` — 动态

| 方法名 | `event` |
|--------|---------|
| 参数 | `pagesize` |
| 期望字段 | `events` |

**响应：**
```json
{
    "code": 200,
    "events": [
        {
            "id": 12345,
            "user": {"userId": 111, "nickname": "User", "avatarUrl": "https://..."},
            "json": "{\"msg\":\"分享了一首歌曲\",\"song\":{\"name\":\"...\"}}",
            "eventTime": 1687000000000
        }
    ]
}
```

#### `event/del` — 删除动态

| 方法名 | `event/del` |
|--------|-------------|
| 参数 | `evId`, `cookie` |

**响应：**
```json
{"code": 200}
```

#### `event/forward` — 转发动态

| 方法名 | `event/forward` |
|--------|-----------------|
| 参数 | `evId`, `uid` (转发者UID), `forwards` (转发语), `cookie` |

**响应：**
```json
{"code": 200}
```

#### `share/resource` — 分享资源

| 方法名 | `share/resource` |
|--------|------------------|
| 参数 | `id`, `type` (song/playlist/album/artist等), `msg`, `cookie` |

**响应：**
```json
{"code": 200}
```

#### `batch` — 批量请求

| 方法名 | `batch` |
|--------|---------|
| 参数 | `batchData` (JSON 字符串: `{"api1": params, "api2": params}`) |

**响应：**
```json
{
    "code": 200,
    "api1": {"code": 200, "data": [...]},
    "api2": {"code": 200, "data": [...]}
}
```

#### `api` — 通用透传（直接调用原始路径）

| 方法名 | `api` |
|--------|-------|
| 参数 | 透传到原始 Netease API 路径（因 Provider 实现而异） |

---

## 8. 不支持的功能处理

在 `apiMap` 中将不支持的方法映射为 `"unsupported"`：

```json
{
    "apiMap": {
        "like": "unsupported",
        "personal_fm": "unsupported",
        "mv/detail": "unsupported"
    }
}
```

**推荐最低支持集：**
- ✅ 必选: `song/url/v1`, `lyric/new`, `song/detail`
- ⭐ 强烈推荐: `cloudsearch`, `album`, `search/hot/detail`, 登录系列, 用户系列, 歌单系列
- 💡 可选: 评论系列, `personal_fm`, `playmode/intelligence/list`, MV 系列

---

## 9. 健康监控与兼容性检查

KMP-PRO 内置 API 健康监控系统，自动对每次调用进行兼容性检查。

### 检查规则

| 检查项 | 规则 | 级别 |
|--------|------|------|
| code 字段 | 必须存在，正常值: 200/0/201/301 | ERROR |
| 期望数据字段 | 响应必须包含预期的数据字段 | WARNING |
| 空数组/对象 | 数据字段为空 | WARNING |
| 播放 URL 有效性 | `url` 字段必须为合法 http URL | ERROR |
| 响应时间 | > 5 秒 | WARNING |
| 响应格式 | 必须为合法 JSON | ERROR |

### 期望字段表

| API 方法 | 期望字段 |
|---------|---------|
| `cloudsearch` | `result` |
| `user/playlist`, `user/playlist/create`, `user/playlist/collect` | `playlist` |
| `user/detail` | `profile` |
| `user/cloud` | `data` |
| `likelist` | `ids` |
| `recommend/songs` | `data` |
| `recommend/resource` | `recommend` |
| `playlist/detail` | `playlist` |
| `playlist/track/all` | `songs` |
| `album` | `album` |
| `artist/detail` | `data` |
| `artist/songs` | `songs` |
| `artist/album` | `hotAlbums` |
| `song/detail` | `songs` |
| `song/url/v1`, `song/url/v1/302` | `data` → `.url` |
| `lyric/new` | `lrc` → `.lyric` |
| `comment/*` | `comments` |
| `msg/private`, `msg/private/history` | `msgs` |
| `mv/detail`, `mv/url` | `data` |
| `dj/program` | `programs` |
| `dj/hot`, `dj/recommend` | `djRadios` |
| `toplist`, `toplist_detail` | `list` |
| `top_playlist`, `top_playlist_highquality` | `playlists` |
| `banner` | `banners` |
| `user/record` | `allData` |
| `user/follows` | `follow` |
| `user/followeds` | `followeds` |
| `user/event`, `event` | `events` |
| `artist/top/song` | `songs` |
| `artist/mv` | `mvs` |
| `artist/list` | `artists` |
| `album/new`, `album/newest` | `albums` |
| `playlist/catlist` | `categories` |
| `playlist/hot` | `tags` |
| `playlist/subscribers` | `subscribers` |
| `record/recent/song` | `data` |

### 开发建议

1. **始终返回 `code: 200`** — 最可靠的成功标识
2. **数据放在期望字段中** — CPPlayer 优先读取这些字段
3. **错误时返回有意义的 `msg`** — 如 `{"code": 404, "msg": "歌曲不存在"}`
4. **不要省略 `code` 字段** — 缺少会导致 ERROR 级别警告
5. **测试你的 Provider** — 导入后在设置中检查健康状态

### 查看健康状态

**设置 → 调试 → 健康状态** 包含：
- **概览 Tab**: 健康评分 (0-100)、警告/错误数
- **方法 Tab**: 按 API 方法的调用统计
- **日志 Tab**: 每次调用的详细记录
- **测试 Tab**: 手动测试单个 API 方法

---

## 10. 模块打包与分发

### 10.1 目录结构

**HTTP Provider:**
```
my-provider.zip
└── manifest.json
```

**Binary Provider:**
```
my-provider.zip
├── manifest.json
├── my-binary              (可执行文件)
└── lib/
    ├── arm64-v8a/
    │   └── libfoo.so      (可选依赖库)
    └── armeabi-v7a/
        └── libfoo.so
```

**JNI Provider:**
```
my-provider.zip
├── manifest.json
└── lib/
    ├── arm64-v8a/
    │   └── libmyapi.so
    └── armeabi-v7a/
        └── libmyapi.so
```

### 10.2 更新

配置 `updateUrl` 可实现自动更新检查：

**GET 响应：**
```json
{
    "version": "1.1.0",
    "downloadUrl": "https://example.com/provider-v1.1.0.zip",
    "changelog": "修复xxx，新增yyy"
}
```

更新时，`user_data/` 子目录会被保留（用于存储用户配置）。

---

## 11. 调试与测试

### 推荐开发流程

1. **创建 HTTP Provider** 作为开发原型
2. **实现必选 API** → 验证核心播放功能
3. **逐步添加可选 API** → 解锁更多功能
4. **检查健康状态** → 修正所有 WARNING/ERROR
5. **打包分发**

### 快速测试脚本

```bash
# 测试根路径
curl http://localhost:3000/

# 测试必选 API
curl -X POST http://localhost:3000/song/url/v1 \
  -H "Content-Type: application/json" \
  -d '{"id":"123456","level":"standard"}'

# 测试歌词
curl -X POST http://localhost:3000/lyric/new \
  -H "Content-Type: application/json" \
  -d '{"id":"123456"}'

# 测试歌曲详情
curl -X POST http://localhost:3000/song/detail \
  -H "Content-Type: application/json" \
  -d '{"ids":"123456,789012"}'

# 测试 /api/ 前缀路由（BinaryProvider 兼容）
curl -X POST http://localhost:3000/api/song/url/v1 \
  -H "Content-Type: application/json" \
  -d '{"id":"123456","level":"standard"}'

# 测试健康检查
curl http://localhost:3000/health
```

### 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 所有响应显示 "缺少code字段" | 响应格式不符合规范 | 确保 JSON 根对象包含 `code` 字段 |
| URL 获取返回 null | 没有版权或参数错误 | 检查歌曲 ID 和 level 参数 |
| 搜索结果为空 | 搜索接口名称不匹配 | 检查 apiMap 中的映射 |
| Binary 启动失败 | 架构不匹配 | 编译对应平台的二进制 |
| 健康评分低 | 期望字段缺失 | 将数据放在正确的期望字段中 |
