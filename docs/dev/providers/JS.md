# JavaScript / TypeScript Provider

> 与 Kotlin 的差异：入口全局、受控 `context`、单脚本打包。适用读者：JS / TS 作者。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-14 · 事实源 `provider-sdk-kmp/js/`

当前入口是 `globalThis.provider`，不是旧 `CPPlayer` 全局、默认导出探测或 Standard JS v1。

## 1. 业务面

```javascript
globalThis.provider = {
  capabilities: ["tracks.get", "tracks.resolve"],
  async get(request, context) {
    return { id: request.id, title: "Track", artists: [], durationMs: 9500 };
  },
  async resolve(request, context) {
    return {
      url: "https://audio.example.com/track.wav",
      actualQuality: "lossless",
      headers: [{ name: "X-Stream-Token", value: "stream-scoped-token" }],
      format: "WAV"
    };
  }
};
```

上例只说明数据形状，不是可用的外部音乐服务。可运行版本见 [`examples/js/index.js`](../../../provider-sdk-kmp/examples/js/index.js) 与 [QUICKSTART](QUICKSTART.md)。

| 方法 | 输入 | 输出 |
|---|---|---|
| `search(request, context)` | `{keyword, limit, offset}` | `{items: Track[], total?, hasMore?}` |
| `get(request, context)` | `{id}` | `Track` |
| `getMany(request, context)` | `{ids: string[]}` | `{items: [{id, track?, failure?}]}` |
| `resolve(request, context)` | `{id, quality}` | `MediaResource` |

| 规则 | 说明 |
|---|---|
| `getMany` | 可省略，宿主适配器会调 `get`；只有真批量接口才声明 `tracks.getMany` |
| 序列化 | 结果直接跨 QuickJS 传对象 / 数组，不需要 stringify / parse |
| 上游 JSON | 可以自己解析；这不等于恢复旧宿主 JSON 协议 |
| 跨桥二进制 | `Int8Array` 或 `Uint8Array`，不用普通数组、路径或 base64 |
| 无参操作 | `Bytes` 请求到达时是 `{ value: {} }`；不要判断 `request.value.byteLength` |

## 2. 上下文

每个方法收到只读 `context`：`providerId`、`accountId`、`settings`、`credential`。

```javascript
const response = await context.http({
  url: context.settings.origin + "/catalog.json",
  method: "GET",
  headers: [{ name: "Authorization", value: "Bearer " + context.credential }]
});
if (response.status !== 200) {
  throw Object.assign(Error("Catalog request failed"), { category: "Network" });
}
const catalog = JSON.parse(context.text(response.body));
```

| 成员 | 语义 |
|---|---|
| `http(req)` | 返回 `{status, headers, body}`；`body` 是字节数组，不是浏览器 Response |
| `sha256(bytes)` | 返回字节数组；不是旧 `crypto` 全套加解密 |
| `utf8(text)` / `text(bytes)` | UTF-8 转换，解码严格校验 |
| `read(key)` / `write(key, bytesOrNull)` | 当前会话临时存储，隔离且有界 |

| 登录相关 | 说明 |
|---|---|
| 登录态来源 | `context.credential` 非空即已登录 |
| 会话重建 | 宿主在 `authenticate` 返回 `cookie` 后保存凭证并重建会话 |
| 禁止 | 登录态只记在模块变量里，否则登录成功后 `getCurrentAccount` 仍返回未登录、`getUserPlaylists` 永远为空 |
| `getUserPlaylists` 的 `id` | 可能是宿主账号键（实测形如 `9084388061`），按「当前登录用户」处理 |

| 不存在 | 说明 |
|---|---|
| 全局 `fetch` | 用 `context.http` |
| 旧 `env.settings` | 用 `context.settings` |
| 宿主文件系统 / 数据库 | 只有会话临时存储 |
| 自动凭证 | HTTP 不自动带凭证、不自动跟随重定向 |
| 自动 Cookie 泛发 | 需要认证时按服务显式设置 Header，不能把 API Cookie 发到媒体域名 |

## 3. 类型与打包

| 资源 | 位置 |
|---|---|
| 手写声明 | [`js/index.d.mts`](../../../provider-sdk-kmp/js/index.d.mts) |
| 生成声明 | [`js/types.generated.ts`](../../../provider-sdk-kmp/js/types.generated.ts) |
| 作者侧 helper | [`js/index.mjs`](../../../provider-sdk-kmp/js/index.mjs) 导出 `defineProvider` |
| 编译期示例 | [`js/contract.test.mts`](../../../provider-sdk-kmp/js/contract.test.mts) |

所有业务方法与 DTO 字段都有生成注释。`KnownCapability` 提供已知能力名补全，未知字符串仍可用于向前兼容。`defineProvider` 只做作者侧「声明的已知能力有对应函数」检查；宿主仍独立校验输入、输出会话。

| 打包规则 | 内容 |
|---|---|
| 单脚本 | 运行时只加载 manifest 指向的单个脚本，不扫描 `libs`、不猜入口 |
| 入口大小 | ≤ 1 MiB |
| 编码 | 脚本、标识符、元数据均 UTF-8 |
| 依赖 | 先 bundle 作者侧 import；不把 Node 或 `node_modules` 打进包；运行时无 Node 模块加载器 |
| 安装包内容 | 入口脚本与元数据，不带平台 DLL |
| `close` | 运行时不调用 JS `provider.close`；引擎与存储由宿主释放 |

字段级规则（optional / null、空数组默认值、safe integer）见 [SDK_TYPES](SDK_TYPES.md)。

## 4. 错误与安全

JS 抛带 `category` 的 `Error`，类别与 Kotlin 相同：`Unsupported`、`NotFound`、`Authentication`、`Network`、`RateLimit`、`Timeout`、`InvalidResponse`、`Runtime`。

| 规则 | 内容 |
|---|---|
| 批量结果 | 每个结果对应原输入同位置；失败不能靠删掉该位置表示 |
| 凭证 | 不进错误文本 |
| 业务失败 | 不伪装成空列表成功 |
| 引擎 | QuickJS 线程、超时、并发、释放由宿主会话负责；不需要自己实现进程管理、HTTP 桥或重试瀑布 |

作者侧验证：

```powershell
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
```

这是本仓库的验证路径，版本由 `package-lock.json` 固定。独立 TS 项目可直接用自己的 `tsc` 与包导出的类型。

## 相关

- 契约与宿主边界：[GUIDE](GUIDE.md)
- 分域做法：[WORKFLOWS](WORKFLOWS.md)
- 字段参考：[SDK_TYPES](SDK_TYPES.md)
