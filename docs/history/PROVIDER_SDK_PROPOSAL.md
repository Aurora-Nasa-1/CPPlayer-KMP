# Provider SDK 设计提案：业务函数优先，协议细节下沉

> 状态：第一阶段预览版已落地，入口见 [`provider-sdk/README.md`](../../provider-sdk/README.md)。
> 下文是完整目标设计，不代表每项能力都已实现；当前支持范围以 SDK README 为准。
> 日期：2026-11-10。
> 范围：降低第三方音源开发成本，不替换现有 Standard v3 / Legacy 传输协议。

## 1. 结论

新增一层官方 **Provider SDK**。插件作者实现有类型的业务函数，直接返回歌曲、分页、
播放地址和歌词对象；SDK 负责请求转换、响应包装、序列化、路由、错误转换和清单生成。

HTTP、JS、JNI/JNA 是运行与传输方式，不应该分别要求作者学习一套业务契约。
第一阶段优先支持现有 QuickJS 的 JS/TypeScript 开发和 Node HTTP 部署；
原生与其他语言后续复用同一契约，不在首版同时建设多套 SDK。

这不是“禁止 JSON”：上游平台的数据格式仍需要理解，但 CPPlayer 自己的 JSON 包装
和 HTTP 返回不再由每个 Provider 重复实现。

## 2. 现有问题与源码依据

| 问题 | 当前实现 | SDK 应承担的工作 |
|------|----------|-------------------|
| 插件入口是字符串协议 | `BackendProvider.callApi(method, Map<String, String>): String` | 对作者暴露业务方法与 DTO，字符串只留在边界 |
| 参数需要记忆历史约定 | `MusicApiServiceImpl.getSongDetail` 发 `ids` 逗号串；播放发 `level` | 转成 `string[]`、`quality`、有类型的分页参数 |
| JS 方法别名不等于完整适配 | `JsBackendProvider.__cp_dispatch` 找到函数后直接 `fn(params)`，再 `JSON.stringify` | 同时适配参数和结果，不能只改方法名 |
| JS 指南与宿主调用存在落差 | 指南的详情函数读 `id`、返回裸曲目；实际详情调用发 `ids`，上层按曲目数组解析 | 用生产调用链的契约测试约束 SDK 与示例 |
| 响应字段需要作者查表 | 搜索、详情、取流有不同容器；健康检查读取 `code` 等字段 | 自动生成宿主能消费的唯一规范形状 |
| 配置与能力存在多份声明 | 业务实现、`apiMap`、`capabilities`、设置清单各自维护 | 从一个 Provider 定义生成派生字段，并验证完整性 |

源码入口：

- `core/src/commonMain/kotlin/cp/player/core/provider/BackendProvider.kt`
- `core/src/commonMain/kotlin/cp/player/core/api/MusicApiServiceImpl.kt`
- `core/src/jvmMain/kotlin/cp/player/core/provider/JsBackendProvider.kt`
- `core/src/commonMain/kotlin/cp/player/core/music/UnifiedMusicSourceImpl.kt`
- `core/src/commonMain/kotlin/cp/player/core/provider/ModuleManifest.kt`

现有 `JsBackendProviderTest` 主要验证桥接器直接调用，并不能证明真实搜索、详情、
预加载和队列恢复链路全部可用。SDK 验收必须补充上层调用链测试。

## 3. 作者看到的接口

以下示例用于展示目标体验，不是当前可直接安装的包。`upstream` 表示作者已有的上游
适配代码，负责真实平台请求及字段映射。

```typescript
import { defineProvider } from "@cpplayer/provider-sdk";
import { upstream } from "./upstream";

export default defineProvider({
  id: "my-music",
  name: "My Music",
  version: "1.0.0",

  tracks: {
    async search({ keyword, limit, offset }, ctx) {
      const page = await upstream.search({ keyword, limit, offset }, ctx);
      return {
        items: page.tracks,
        total: page.total,
        hasMore: page.hasMore,
      };
    },

    async get(id, ctx) {
      return upstream.getTrack(id, ctx);
    },

    async resolve({ id, quality }, ctx) {
      return upstream.resolveTrack({ id, quality }, ctx);
    },
  },
});
```

最小闭环仍然是搜索、详情、取流，不把缺失的详情能力伪装成“搜索后就能完整播放”。
作者不写 `code`、`data`、`res.json()`、HTTP 路由、`ids.split(",")` 或 `apiMap`。

### 业务模型

- `Track`：原始字符串 `id`、`title`、`artists`、可选专辑/封面、明确单位的 `durationMs`。
- `Page<T>`：`items`、可选 `total` / `hasMore`；未知分页状态不能伪造成确定的 `false`。
- `MediaSource`：`url`、可选请求头、实际音质、格式、码率、有效期。
- `Lyrics`：明确的格式及文本，可选翻译/注音。
- `ProviderContext`：本次调用的配置与会话、HTTP 客户端、隔离存储、脱敏日志。

SDK 对作者提供正常的 number / boolean / 数组类型，适配器才处理宿主线上的字符串参数。
资源 ID 不转为 number，不去掉前导零，不转小写；宿主媒体 ID 与上游原始 ID 分开处理。
DTO 字段语义与现有领域模型对齐，但不直接导出整个 `core`，避免把 UI、JVM 类型或历史
`Long` 兼容字段带进插件 SDK。

## 4. 两种繁琐分别解决

### 4.1 CPPlayer 的返回包装：完全由 SDK 接管

```text
宿主方法 + 字符串参数
  -> 请求适配器
  -> 插件业务函数
  -> 结果校验器
  -> Standard v3 响应编码器
  -> JS 返回值 / HTTP body / 原生边界字符串
```

例如宿主的 `song/detail` 请求含 `ids: "a,b"`，适配器调用 `tracks.getMany(["a", "b"])`，
返回宿主可识别的 `{ code: 200, data: { tracks: [...] } }`。

作者只实现 `get(id)` 时，SDK 提供有并发上限的批量实现；作者可实现 `getMany(ids)` 覆盖。
缺失曲目、部分失败与整批失败须明确区分，不把网络故障统一转成空成功。
映射规则与每个方法的响应形状集中维护，不散落在多个运行时的分发器里。

搜索歌曲、歌手、专辑、歌单分别声明对应 handler；不能把所有搜索类型都落到歌曲搜索，
再用空结果伪装成已实现。播放的直链、重定向与下载方法别名必须明确列入映射与测试。

### 4.2 上游 HTTP / JSON：提供便利客户端，保留业务判断

拟议调用方式：

```typescript
const result = await ctx.http.getJson<UpstreamSearchResponse>(
  "/search",
  { query: { q: keyword, limit, offset } },
);

const result2 = await ctx.http.postJson<UpstreamResolveResponse>(
  "/resolve",
  { json: { id, quality } },
);
```

统一处理基地址、查询编码、JSON Content-Type、序列化、响应解析、超时和 HTTP 状态。
HTTP 非成功响应、网络失败、空 body 和无效 JSON 必须可区分，包含脱敏的端点与诊断信息。
上游 `HTTP 200 + 业务错误码` 仍由平台适配器解释；SDK 不猜每个平台的业务规则。

泛型只改善编译期提示，不等于运行期校验。SDK 自身业务 DTO 做边界校验；上游响应允许
额外传入解析/校验函数。字段改名、秒转毫秒、多歌手映射仍是必要的平台适配工作。

首版不设计 JSONPath/YAML 全能映射语言。简单平台用普通映射函数，复杂签名、加密和
多步请求也继续用代码表达，避免把一种繁琐变成另一种难调试的配置。

## 5. 一份业务定义，多种部署

| 产物 | 运行方式 | 作者是否处理 HTTP 返回 |
|------|----------|--------------------------|
| QuickJS 插件包 | 构建为单脚本，注册 `globalThis.CPPlayer.callApi` | 否，不需要自建服务器 |
| Node HTTP 服务 | HTTP adapter 注册路由、读取请求、编码响应 | 否，只启动 SDK 提供的服务适配器 |
| 后续 Rust SDK | 有类型 trait / struct，派生或生成边界胶水 | 否，SDK 接管 JSON 与 ABI 胶水 |
| 现有手写 Provider | 继续走旧协议与旧入口 | 不强制迁移 |

JS 构建产物应包含需要的 SDK 代码，不依赖宿主存在 Node、npm、文件系统模块或 ESM
模块解析能力。TypeScript 是开发语言，分发给 QuickJS 的仍是编译后的 JavaScript。
现有直接求值入口不能被描述成已经能加载任意 npm 包或直接执行 `.ts`。

统一的是业务语义和协议测试，不保证 Node 平台专属依赖能直接在 QuickJS 中运行。
原生 SDK 不要求所有作者换成 JS，也不在第一阶段更换 JNI/JNA ABI。

## 6. 错误、上下文与能力

### 错误

提供 `NotFound`、`Unsupported`、`AuthRequired`、`RateLimited`、`UpstreamError`、
`InvalidResponse` 等明确错误类别，编码器映射到现有宿主能理解的响应。
不能仅给错误换名字而不验证宿主的实际分类；扫码、网页登录等状态后续独立建模，
不靠通用异常吞掉 pending / expired / denied。

取消不转换成普通业务错误。现有同步 `callApi` 边界和 JS fetch 桥接尚不提供端到端
取消保证，SDK 不能承诺单靠包装就能中断底层请求；真正取消需要后续宿主生命周期改造。
只对明确允许且幂等的请求重试，写操作与登录操作不默认自动重放。

### 配置、会话与存储

每次请求形成独立上下文快照，避免模块初始化时的配置快照长期失效。
共享 HTTP 服务不能把所有客户端放进一个全局登录态，也不能只按 provider ID 隔离账号。
SDK 应支持显式会话作用域；当前传输缺少足够身份信息时，先补契约，不能假装已隔离。
日志脱敏 Cookie、Authorization、令牌与签名 URL；配置变更后缓存及连接状态不得串用。
持久化凭据的安全性不能由“按目录分开”推导为“已加密”。

### 能力与清单

Provider 定义是单一来源，构建工具生成合法 `manifest.json`、默认 `apiMap` 和设置 schema。
初版使用受限制的静态元数据定义，不为提取清单而执行带网络/持久化副作用的插件入口。
设置支持开发期 boolean / number / enum，生成时转换为当前宿主要求的字符串默认值。

能力按完整 handler 组合验证，例如 `playback` 必须具备详情与取流；功能分组可以显式选择，
但不能把只实现一半的分组自动标成已支持。未实现方法明确返回 Unsupported。
生成 capabilities 不等于完成 UI 门控，现有宿主入口限制仍按真实实现说明。

## 7. 兼容与落地边界

推荐先采用**随插件打包的 SDK 包装器**：

1. 在插件构建期把 `defineProvider` 编译成现有 `callApi` 入口。
2. 首版输出当前宿主支持的请求/响应形状，不依赖重新解释 `contract` 或 `apiVersionTag`。
3. 保持旧 JS 插件原样返回，不给所有旧返回值统一套 `code/data`，避免二次包装与行为变化。
4. 保留 HTTP/JNI/Legacy 现有路径；SDK 版本与宿主协议版本分开管理。
5. 若后续使用新增宿主桥接能力，先发布具备该能力的宿主，再提升 `minHostApiVersion`。

这样可以先降低作者成本，再逐步改善宿主内部的 JSON 依赖；首版不重写
`MusicApiService`、缓存体系和所有 Provider，也不直接把 `contract` 从展示字段改成行为开关。

## 8. 实施顺序与验收

### 第一阶段：一个真实可播放的垂直切片

- 拟建 `provider-sdk/`：DTO、Provider 定义、参数适配、结果编码、错误类别、HTTP helper。
- 提供 QuickJS 与 Node HTTP 两个薄运行适配器，复用同一业务定义与协议夹具。
- 提供示例项目和构建命令，生成单脚本及 ZIP 根目录的清单，不要求作者手填派生字段。
- 用上游 fixture 测试失败场景，并用合法真实音频完成一次装包、搜索、详情、播放验证。

优先验收搜索、详情、取流；歌词随后加。首版不铺开评论、私信、扫码登录或所有语言 SDK。

### 第二阶段：开发体验

拟议 CLI 提供 `init`、`dev`、`test`、`pack`。首版重点是类型提示、契约测试和有效包产物。
开发重载只作用于开发 Provider，不能复用在运行中的 classpath 上直接覆写 app/core 编译产物。

### 第三阶段：扩展业务与语言

按歌单、歌手/专辑、账号等分组扩展；再从同一机器可读契约生成其他语言 DTO 与协议夹具。
机器可读契约负责字段和方法定义，特殊状态与业务行为仍需显式测试，不追求“生成一切”。

### 必须通过的检查

- 宿主真实调用：`MusicApiServiceImpl -> ProviderManager -> Provider -> SDK -> 业务 handler`。
- 返回值继续经过现有解析器和健康检查，不能只断言 SDK 自己的 JSON 看起来正确。
- 覆盖 `ids` 批量详情、`level` 音质、搜索类型与分页、取流别名、缺失可选方法。
- 覆盖非数字 ID、前导零 ID、9.5 秒短音频、空结果、末页、无效 JSON、HTTP/业务错误。
- 覆盖配置变化、会话隔离、敏感信息脱敏、链接过期及批量部分失败。
- 回归旧 JS、HTTP、原生 Provider，确认没有全局重包响应或改变旧协议。
- Kotlin 代码落地后，使用会话隔离的构建目录编译 `:app:compileKotlinDesktop` 与
  `:core:compileKotlinDesktop`；测试结果以 XML 为准，检查失败与 skipped 数量。
- QuickJS 桌面测试不能代替 Android 装包验证；HTTP adapter 使用同一组契约夹具复测。

成功标准：开发者接入一个新平台时，主要编写“怎么请求上游、怎么映射歌曲、怎么取得音频”，
而不是再次实现 CPPlayer 的路由、JSON 包装、清单映射和错误协议。
