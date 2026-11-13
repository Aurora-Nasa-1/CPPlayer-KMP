# Provider 开发指南：KMP SDK

更新：2026-11-10。当前唯一可安装的音源契约是 **`kmp-sdk-v1`**。
这里的 `v1` 是 SDK 契约主版本，**不是已退役的 Legacy Provider v1 或 Standard v3**。

先看 [快速上手](QUICK_PROVIDER_DEV_GUIDE.md)，清单字段以
[Manifest 参考](MANIFEST_REFERENCE.md) 为准。SDK 源码位于
[`provider-sdk-kmp`](../../provider-sdk-kmp)，不依赖 core、UI 或应用数据库。

### 参考入口

| 内容 | 文档 / 声明 |
|---|---|
| 从零运行与打包 | [快速上手](QUICK_PROVIDER_DEV_GUIDE.md) |
| 每个方法、能力名、输入输出与行为 | [完整操作参考](SDK_OPERATIONS.md) |
| 全部 DTO 字段、单位、空值、默认值与限制 | [字段与类型参考](SDK_TYPES.md) |
| 评论/私信、一起听、视频/DJ、歌单、上传、账号的调用流程 | [分域实现指南](PROVIDER_WORKFLOWS.md) |
| Kotlin 接口与 IDE 注释 | [MusicProvider](../../provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/MusicProvider.kt)、[扩展操作](../../provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/ExtendedOperations.generated.kt) |
| TypeScript 类型与 IDE 注释 | [types.generated.ts](../../provider-sdk-kmp/js/types.generated.ts) |
| ZIP、权限、设置与更新 | [Manifest 参考](MANIFEST_REFERENCE.md) |

## 1. 开发者只实现业务

```text
页面 / 播控 / 下载 / 集成
  -> Repository / UnifiedMusicSource
  -> ProviderHost（账号会话、取消、超时、校验）
  -> SDK MusicProvider
  -> Kotlin / JavaScript / Native 适配器
  -> 自己的上游服务
```

不再开发 `callApi`、`apiMap`、本地 HTTP 服务、JNI 服务端入口、
网易云兼容响应或 `{code, data}` 包装。解析自己上游的 JSON 是正常业务，
但不要把上游格式作为宿主协议返回。

### 当前支持范围

| SDK 方法 | 能力名 | 返回值 |
|---|---|---|
| `capabilities()` | 无 | `Capabilities`，会话内不可变 |
| `search(SearchRequest)` | `tracks.search` | `TrackPage` |
| `get(IdRequest)` | `tracks.get` | `Track` |
| `getMany(BatchRequest)` | `tracks.getMany`，或使用 `tracks.get` 的默认逐项实现 | `TrackBatch` |
| `resolve(ResolveRequest)` | `tracks.resolve` | `MediaResource` |
| `close()` | 无 | 释放会话资源 |

**已支持只读目录能力**：远端歌词、歌单搜索/详情/曲目/用户歌单、专辑搜索/详情、
歌手搜索/详情/曲目/专辑。Kotlin、JavaScript 与 JNI/Native 适配器使用同一业务契约。

**扩展业务契约**：已接入登录、推荐、评论/私信、一起听、MV/视频、DJ、日历/动态、
账号/云盘和目录写操作。共 161 个业务操作，完整签名与能力名由 schema 生成到
[SDK 操作表](SDK_OPERATIONS.md)。新增 Kotlin 常量位于 `ExtendedCapability`；
旧常量与协议编号不变，未实现方法默认返回 `Unsupported`。
这些是可实现、可调用的 SDK 能力，不代表某个生产音源已经完成上游实现或真机联调。
远端音质枚举通过 `media.qualities` 声明；通用原始端点透传和 JSON batch 仍不开放，
不回退到旧协议。游标分页、上传边界与剩余范围见 [移植状态](SDK_MIGRATION_STATUS.md)。
本地媒体、边车歌词、AMLL 与独立 Lyrico 歌词插件不因此退役。
不要仅在 manifest 声明某项能力，就承诺这些功能已接通。

扩展的请求对象方法由 `UnifiedMusicSource` 按当前账号会话调用，ID 使用 Provider 原始
字符串，不要混入 `CPMediaId`。面向命名空间 ID 的视频播放入口是 `resolveVideoResource`，
它先核对 Provider，避免把另一个音源的裸 ID 发给当前音源。
私信正文为纯文本，游标不解释、不转换，消息页顺序为新到旧。
一起听所有时间都用毫秒；空队列与尚无队列是不同状态。歌单更新的 `tags = null`
表示不改，`tags = emptyList()` 表示清空。

## 2. Kotlin

仓库内依赖 `project(":provider-sdk-kmp")`。当前版本 `0.1.0` 是本地预览，
不是已发布的 Maven 坐标；Android/JVM 为现有目标，不能把 Kotlin 二进制包当作插件 ZIP 安装。

```kotlin
class MyProvider(private val context: ProviderContext) : MusicProvider {
    override suspend fun capabilities() =
        Capabilities(listOf(Capability.SEARCH, Capability.GET, Capability.RESOLVE))

    override suspend fun search(request: SearchRequest): TrackPage =
        catalog.search(request.keyword, request.limit, request.offset)

    override suspend fun get(request: IdRequest): Track =
        catalog.track(request.id)

    override suspend fun resolve(request: ResolveRequest): MediaResource =
        catalog.resolve(request.id, request.quality)
}
```

`catalog` 是音源自己的业务实现；完整可编译示例：
[`CatalogProvider.kt`](../../provider-sdk-kmp/examples/kotlin/CatalogProvider.kt)。
应用通过 `SdkBackendProvider` 注册受信任的 `(ProviderContext) -> MusicProvider` 工厂，
这是宿主装配代码，不是音源作者必须重复实现的接口。

## 3. 数据纪律

- **ID 是不透明、区分大小写的字符串**。保留前导零、逗号、UUID；不转数字、不拆 CSV。
  SDK 只接收裸资源 ID；宿主统一层使用 `{providerId}://song/{id}`。
- `durationMs` 为毫秒，`9500` 就是 9.5 秒，不按数值大小猜秒/毫秒。
- `TrackPage.total` 与 `hasMore` 可空；未知就是未知，不能用当前页长度伪造总量。
- `getMany` 的输出与输入逐项对应，保留顺序和重复项；每项恰好有 `track` 或 `failure`，
  不把单曲不存在变成整批空列表。
- `resolve` 返回真实的 `actualQuality`。格式、码率、大小和到期时间未知时留空，
  不能照抄请求档位或猜固定参数。
- 流媒体请求只使用 `MediaResource.headers` 明确声明的头。
  宿主不会自动把账号 Cookie 加到 CDN、下载、预加载或中继请求。

## 4. 账号、生命周期与错误

每个实例属于不可变的 Provider/账号/设置/凭证版本会话。切换 Provider、选择 SDK 账号、
更新凭证或配置后，旧结果不能发布；会话层取消在途调用、排空任务，再释放适配器。
不要自行跨账号缓存实例或保存旧 `ProviderContext`。

宿主每个会话只读取一次 `capabilities()`；失败或取消不缓存。
Kotlin 的默认 `getMany` 会逐项调用 `get`，保留失败与重复项；只有上游确实提供批量接口时才需要覆盖。

Kotlin 抛 `ProviderException(Failure(category, message))`；
JS 抛带 `category` 的 `Error`。常用类别：
`Unsupported`、`NotFound`、`Authentication`、`Network`、`RateLimit`、`Timeout`、
`InvalidResponse`、`Runtime`。取消必须原样传播，不能吞成空数据。
错误文本不要包含凭证、私有 URL 或请求体。

## 5. 宿主能力与边界

`ProviderContext` 只提供 Provider/账号标识、设置快照、凭证和受控 runtime。
HTTP 必须命中 `sdkOrigins` 的 origin 白名单；不自动注入 Cookie，不自动跟随重定向。
HTTPS 可用，明文 HTTP 仅接受 loopback。存储是有界的**会话内临时存储**，不是持久化数据库。
无宿主文件系统、应用数据库或 UI 权限。
Provider 实现应通过 `ProviderContext.runtime.http` 发起上游请求；JNI 只承载业务 DTO，
不能另行恢复旧 JSON endpoint 映射或把宿主 API 响应封套当成 Provider 契约。

默认宿主请求截止时间 15 秒、并发上限 4；批次/分页上限 500 项。
QuickJS 单会话线程、内存上限 32 MiB、栈 512 KiB、执行预算 250 ms。
这些限制属于宿主，不需要音源重复实现调度器。

## 6. 安装与验证

ZIP 安装支持 SDK JavaScript 与 JNI/Native 包；原生库使用宿主统一 `NativeSession`
入口，无需 `nativeFactoryClass`、宿主预先注册或额外信任授权。
格式见 [Manifest](MANIFEST_REFERENCE.md#原生-sdk-包)。
手动导入与仓库安装都按精确 ID 校验；同名不同 ID 不覆盖。
入口缺失、越界、超限、未知契约等在替换文件前拒绝；更新失败保留旧安装。

```powershell
node provider-contract/generate.mjs --check
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
pwsh -File scripts/verify-provider-chain.ps1
```

最后一条验证脚本仅支持 Windows（调用 `gradlew.bat`）；前面的 Node/npm 命令可在相应工具支持的平台运行。

TS 命令使用新 SDK 包锁定的开发依赖；独立音源项目使用自己的 TypeScript 编译器，
不需要依赖旧 SDK 目录。`--check` 同时检查字段参考、操作参考、
Kotlin/TS 注释和分发代码。修改契约时编辑 `schema.mjs`，语义编辑
`documentation.mjs`，再运行生成器；生成结果不能手工改成另一套声明。

最后一条使用独立构建目录，运行 SDK、统一层、安装、缓存、播放与 UI 仓库回归，
保存 JUnit XML 并要求失败、错误、跳过数都为零。完整 core 测试仍应单独执行；
定向回归不是“全仓测试已通过”的替代品。

## 7. 旧实现如何迁移

1. 从旧实现提取上游业务，重写为 `search/get/resolve`。
2. 删除端点映射、JSON 宿主封套、端口分配和服务启动逻辑。
3. 把账号凭证改为会话上下文，把媒体访问凭证放入显式流头。
4. 按新清单打包并验证；**不能仅把旧包的 contract 改名**。

生产加载器不再接受 HTTP/Binary/JNI/FFM 旧包或旧 JS 协议。
既有用户文件不会自动删除、转换或按同名迁移账号。
旧 `provider-sdk/`、早期设计记录或未完成工作副本中的兼容代码，不是现行开发契约。
`MusicApiService` 与旧 JSON 输出投影已退役；应用直接消费统一层的类型化结果。
