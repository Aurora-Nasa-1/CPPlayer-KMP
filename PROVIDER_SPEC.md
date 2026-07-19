# CP-Player Provider 开发者规范 (V2.0)

> **注意：此文档为全新制定的 V2.0 规范，旧版的打包和 API 标准已全面废弃。**

## 1. 概述
CP-Player Provider 用于为应用提供第三方音源的搜索、解析、播放链路等能力。通过统一的 `CPMediaId` 规范，前端应用可以无缝集成多个 Provider，实现跨平台音源聚合。

## 2. 模块打包格式 (.cppm)
所有第三方 Provider 必须打包为后缀为 `.cppm` 的文件（内部为标准 ZIP 格式）。
包内基础目录结构如下：
```text
/
├── manifest.json       (必需，声明文件)
├── main.js / lib.so    (必需，执行入口)
└── icon.png            (可选，提供商图标)
```

### 2.1 manifest.json 规范
```json
{
  "id": "netease",
  "name": "Netease Cloud Music",
  "version": "1.0.0",
  "author": "Developer",
  "type": "js", // 可选值：js, jni, http, binary
  "entry": "main.js",
  "apiMap": {
    "search": "/api/v1/cloudsearch",
    "songUrl": "/api/v1/song/url"
  }
}
```

## 3. CPMediaId 统一标识规范
Provider 返回的所有数据实体中，只要涉及到唯一标识（歌曲 ID、歌单 ID 等），**必须**组合为 CPMediaId 格式返回给播放器前端。

格式：`{providerId}://{resourceType}/{resourceId}`

- `providerId`: manifest.json 中声明的 `id`
- `resourceType`: 资源类型，如 `song`, `playlist`, `album`, `artist`, `user`
- `resourceId`: 实际的资源 ID

**示例：**
如果你是 B站音频的 Provider (id: bilibili)，获取到的某首歌原始 ID 为 `au12345`。
你返回的数据模型中，`id` 字段应当为：`bilibili://song/au12345`。

## 4. API 响应规范
为了最大程度兼容现有的 Provider 实现（如网易云等复杂 API），我们**不对 Provider 的原始 API 响应格式做强制的结构化包裹转换**。

Provider 应当**尽可能保持其原始的响应结构**，返回原汁原味的 JSON 数据。
CP-Player 核心通过在 KMP 层提供转换适配器（Adapter/Mapper）来将各 Provider 的原始数据统一映射到前端需要的 `TrackSummary` 等领域模型。

*唯一的要求是*：如果原始响应存在业务逻辑化失败，应当通过 HTTP 状态码或抛出异常等方式让宿主感知。

## 5. 多账号与状态隔离
CP-Player 核心会为每个 Provider 自动维护独立的 Cookie / Token 存储空间。
你的 Provider 在接收到请求时，宿主会自动在上下文（环境变量或请求头中）注入属于当前 Provider 的 Token。
请勿在 Provider 内部实现持久化存储逻辑，所有状态需回传给宿主由宿主统一存储。
