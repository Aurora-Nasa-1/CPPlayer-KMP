# JavaScript / TypeScript Provider

更新：2026-11-10。当前入口是 `globalThis.provider` 与 `kmp-sdk-v1`，
不是旧 `CPPlayer` 全局、默认导出探测或 Standard JS v1。

## 业务面

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

这是数据形状示例，不是可用的外部音乐服务。可运行示例：
[`index.js`](../../provider-sdk-kmp/examples/js/index.js) 与
[快速上手](QUICK_PROVIDER_DEV_GUIDE.md)。

| 方法 | 输入 | 输出 |
|---|---|---|
| `search(request, context)` | `{keyword, limit, offset}` | `{items: Track[], total?, hasMore?}` |
| `get(request, context)` | `{id}` | `Track` |
| `getMany(request, context)` | `{ids: string[]}` | `{items: [{id, track? , failure?}]}` |
| `resolve(request, context)` | `{id, quality}` | `MediaResource` |

`getMany` 可省略，宿主适配器会调用 `get`；仅实现真正批量接口时声明 `tracks.getMany`。
结果直接跨 QuickJS 传对象/数组，不需要 stringify/parse。
解析自己上游的 JSON 当然可以；这不等于恢复旧宿主 JSON 协议。

## 上下文

每个方法拿到只读 `context`，包含 `providerId`、`accountId`、`settings`、`credential`：

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

- `http`：返回 `{status, headers, body}`；body 是字节数组，不是浏览器 Response。
- `sha256(bytes)`：返回字节数组；不是旧 `crypto` 全套加解密 API。
- `utf8(text)` / `text(bytes)`：UTF-8 转换，文本解码严格校验。
- `read(key)` / `write(key, bytesOrNull)`：当前会话临时存储，隔离且有界。

无参操作（`Bytes` 请求）实际到达时是 `{ value: {} }` —— 空 `Int8Array` 跨桥后是个空对象，
不是 `Int8Array(0)`，也不需要读它。不要对 `request.value.byteLength` 做判断。

`context.credential` 非空就代表当前会话已登录。宿主在 `authenticate` 返回 `cookie` 后
会保存凭证并**重建会话**，所以登录态不能只记在模块变量里；否则登录成功后
`getCurrentAccount` 仍会返回未登录、`getUserPlaylists` 永远空。
`getUserPlaylists` 的 `id` 可能是宿主的账号键（实测形如 `9084388061`），
不是上游用户 ID，按「当前登录用户」处理即可。

无全局 `fetch`、旧 `env.settings`、宿主文件系统或数据库契约。
HTTP 只允许 manifest 中的 origin，不自动带凭证，不自动跟随重定向。
需要认证时由业务为对应服务显式设置 Header，不能把 API Cookie 泛发到媒体域名。

## TypeScript 与打包

类型定义在 [`js/index.d.mts`](../../provider-sdk-kmp/js/index.d.mts) 和
[`js/types.generated.ts`](../../provider-sdk-kmp/js/types.generated.ts)。
所有业务方法和 DTO 字段都有生成注释；`KnownCapability` 可用于已知能力名补全。
完整输入输出约束见 [字段参考](SDK_TYPES.md)，功能组合见
[分域实现指南](PROVIDER_WORKFLOWS.md)。Kotlin 与 TS 声明使用同一份 schema，
无需另写一份原始 API 响应接口。
可选的 `defineProvider` 只做作者侧能力/方法检查；先 bundle 作者侧 import，
安装包里只放入口脚本及元数据。运行时不提供 Node 模块加载器。

只加载 manifest 指向的单个脚本，不会扫描 `libs` 或猜入口文件。
入口最多 1 MiB，脚本、标识符与元数据须 UTF-8。

跨桥二进制为 `Int8Array | Uint8Array`，不使用普通数值数组、文件路径或 base64 字符串。
optional/null 字段、空数组默认值与 safe integer 的准确规则见字段参考。
运行时不调用 JS `provider.close`；会话引擎和 runtime 存储由宿主释放。

作者侧类型验证示例：

```powershell
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
```

这是本仓库的验证路径，版本由新 SDK 的 package-lock.json 固定。
独立 TS 项目可直接使用自己的 `tsc` 与包导出的类型。

## 错误与安全

JS 抛带 `category` 的 Error，例如 `NotFound`、`Unsupported`、`Authentication`。
每个批量结果对应原输入同位置；失败不能通过删掉该位置表示。
不要把凭证放进错误文本，或把业务失败伪装成空列表成功。

QuickJS 引擎、线程、超时、并发和释放由宿主会话负责。
不需要自己实现进程管理、HTTP 桥或调用重试瀑布。
完整限制与数据语义见 [Provider 指南](PROVIDER_DEV_GUIDE.md)。
