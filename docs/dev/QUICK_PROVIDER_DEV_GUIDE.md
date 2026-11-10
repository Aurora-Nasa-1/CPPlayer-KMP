# 最短路径：开发一个 SDK 音源

更新：2026-11-10。只走 `provider-sdk-kmp`，不需要理解旧 v1/v3 端点表。

## 1. 先跑通真实示例

仓库已提供本地目录服务、9.5 秒 WAV 和 JS 音源，不需要外部音乐平台或账号。

在一个终端启动示例服务：

```powershell
node provider-sdk-kmp/examples/js/upstream.mjs
```

在另一个终端打包：

```powershell
pwsh -File provider-sdk-kmp/examples/js/pack.ps1
```

导入 `artifacts/provider-sdk/kmp-catalog-0.1.0.zip`，搜索 `all` 并播放。
服务只监听 `127.0.0.1:48482`；Android 手动验证需 `adb reverse tcp:48482 tcp:48482`，
否则设备的 localhost 不会指向电脑。

## 2. 真正需要替换的只有业务

从 [`examples/js/index.js`](../../provider-sdk-kmp/examples/js/index.js) 开始：

```javascript
globalThis.provider = {
  capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
  async search(request, context) {
    return catalog.search(request.keyword, request.limit, request.offset, context);
  },
  async get(request, context) {
    return catalog.get(request.id, context);
  },
  async resolve(request, context) {
    return catalog.resolve(request.id, request.quality, context);
  }
};
```

这里的 `catalog` 是你自己的上游业务；仓库中的完整示例可直接运行。
不要返回 `{code, data}`，不要实现 `callApi`、`apiMap` 或 HTTP 服务端。
Kotlin 使用同样的 `MusicProvider`，见 [完整指南](PROVIDER_DEV_GUIDE.md#2-kotlin)。

## 3. 最小安装包

```text
my-provider.zip
  manifest.json
  index.js
```

```json
{
  "id": "my-provider",
  "name": "My Provider",
  "version": "1.0.0",
  "type": "js",
  "contract": "kmp-sdk-v1",
  "entryPoint": "index.js",
  "capabilities": ["search", "playback"],
  "sdkOrigins": ["https://music.example.com"]
}
```

`sdkOrigins` 必须覆盖实际请求 origin；清单里的 UI 标签不能替代脚本中的方法能力声明。
脚本须 UTF-8、最多 1 MiB。TypeScript 先打包成单文件，不把 Node 或 `node_modules` 塞进插件。

## 4. 四条最重要的约定

1. ID 永远是字符串；`001`、`A`、`a` 和 `A,a` 都不能被改写。
2. 时长统一毫秒，批量详情保序、保留重复项、逐项返回失败。
3. 流返回真实档位和显式请求头，不让宿主猜音质或替你加账号 Cookie。
4. 未接入的能力返回 `Unsupported`，不要偷偷切回旧协议。

当前 SDK 契约含 160 个 typed 业务操作，包含登录、目录读写、推荐、评论/私信、
一起听、MV/视频、DJ、日历/动态与账号操作。见 [完整操作表](SDK_OPERATIONS.md)；
这不表示任何一个音源已实现全部能力。Kotlin 新增能力常量见 `ExtendedCapability`，
JS 按操作表声明能力名并实现对应方法，默认实现仍返回 `Unsupported`。
通知、游标评论、多类型搜索、排序/导入、账号绑定和分块上传见
[移植状态](SDK_MIGRATION_STATUS.md)。远端音质枚举使用 `media.qualities`；
通用原始端点透传和 JSON batch 仍不开放。
本地媒体、边车与独立歌词插件照常使用。

## 5. 验证

```powershell
pwsh -File scripts/verify-provider-chain.ps1
```

更多数据类型、错误处理、生命周期与边界：
[Provider 指南](PROVIDER_DEV_GUIDE.md) · [JS 指南](JS_PROVIDER_DEV_GUIDE.md) ·
[操作参考](SDK_OPERATIONS.md) · [字段参考](SDK_TYPES.md) ·
[分域流程](PROVIDER_WORKFLOWS.md) · [Manifest 参考](MANIFEST_REFERENCE.md)。
