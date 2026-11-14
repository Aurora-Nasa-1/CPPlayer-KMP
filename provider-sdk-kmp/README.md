# KMP Provider SDK（预览）

> Provider 强类型契约、数据类型与受控 runtime。不依赖 core、Compose 或宿主数据库。
> 版本 `0.1.0` 是本地预览，不是已发布的 Maven 坐标。

主链路：统一 Kotlin 数据层 → KMP SDK → Kotlin / JS / native 适配器 → Provider。作者只实现业务方法，不写 `callApi`、`apiMap`、CPPlayer JSON 封套或 v1/v3 响应约定。

## 1. Kotlin

仓库内依赖 `project(":provider-sdk-kmp")`。发布任务由 `maven-publish` 提供；`publishAllPublicationsToProviderPreviewRepository` 把文件式预览仓库写到 `artifacts/provider-sdk/maven`，不发远端、不写用户 Maven 本地缓存。目标为 Android 与桌面 JVM，不提供 Kotlin/Native 目标。

```kotlin
class MyProvider(private val context: ProviderContext) : MusicProvider {
    override suspend fun capabilities() =
        Capabilities(listOf(Capability.SEARCH, Capability.GET, Capability.RESOLVE))

    override suspend fun search(request: SearchRequest): TrackPage =
        catalog.search(request.keyword, request.limit, request.offset)

    override suspend fun get(request: IdRequest): Track = catalog.track(request.id)

    override suspend fun resolve(request: ResolveRequest): MediaResource =
        catalog.resolve(request.id, request.quality)
}
```

| 项 | 说明 |
|---|---|
| `getMany` | 有可取消、保序的逐项默认实现；上游有真批量接口才覆盖 |
| NotFound | 是 typed `ProviderException`，不是空 `Track` |
| 可编译示例 | [`examples/kotlin/CatalogProvider.kt`](examples/kotlin/CatalogProvider.kt) |
| 上游请求 | 走 `ProviderContext.runtime.http`，origin 限制与会话归属留在宿主 |
| 注册 | 宿主用 `SdkBackendProvider` 与工厂 `(ProviderContext) -> MusicProvider` 注册可信编译产物；这是宿主关注点，不是作者必实现的接口 |
| JNI | 承载同一组 DTO 操作，不得重新引入端点映射或宿主 JSON 封套 |

JVM 产物包含同一契约生成的 typed JNI 会话适配器。JNI 包可直接安装，无需宿主注册或信任授权；原生库用标准 `NativeSession` JNI 导出即可，无需 JAR 或 `nativeFactoryClass`，自定义 `NativeProviderFactory` 适配器是可选路径。见 [原生 SDK 包](../docs/dev/providers/MANIFEST.md#5-原生-sdk-包)。

## 2. JavaScript / TypeScript

在 `globalThis.provider` 上实现同名业务方法，每个方法收请求对象与不可变的账号作用域 context。

```javascript
globalThis.provider = {
    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
    async search(request, context) { return catalog.search(request, context); },
    async get(request, context) { return catalog.track(request.id, context); },
    async resolve(request, context) { return catalog.resolve(request, context); }
};
```

| 项 | 说明 |
|---|---|
| 可运行示例 | [`examples/js/index.js`](examples/js/index.js) 含真实 HTTP 调用 |
| Context 能力 | HTTP、SHA-256、UTF-8 转换、会话存储；不暴露宿主文件系统或数据库 |
| 字节 | 以 typed array 跨 QuickJS（Kotlin `ByteArray` 表现为 `Int8Array`） |
| 结果传递 | 业务结果为对象 / 数组，不 stringify / parse；解析自家上游 JSON 属正常代码 |
| 类型声明 | `js/index.d.mts` 与 `js/types.generated.ts` |
| 作者侧检查 | `js/index.mjs` 导出 `defineProvider`，校验能力与方法的对应关系 |
| 打包 | 安装前 bundle 作者侧 import；安装包不带平台 DLL，也不需要 Node |

```powershell
node provider-sdk-kmp/examples/js/upstream.mjs
# 另一个 shell：
pwsh -File provider-sdk-kmp/examples/js/pack.ps1
```

导入 `artifacts/provider-sdk/kmp-catalog-0.1.0.zip`，搜索 `all`，播放本地生成的 9.5 秒音。服务只绑定 `127.0.0.1:48482`；Android 上该地址指设备本身，手动验证需 `adb reverse`。预览 runtime 刻意不开放跨设备 HTTP。

## 3. 验证

```powershell
pwsh -File scripts/verify-provider-sdk.ps1
pwsh -File scripts/verify-provider-sdk.ps1 -Android
```

Node 与已配置的 JDK / Android SDK 由各自的闸门要求。原生适配器验证独立于这两条命令。夹具服务是本地生成的目录与音频源，不是公共音乐服务，也不构成「生产 Provider 已完整迁移」的证据。

契约 DTO 与编解码由 `provider-contract/schema.mjs` 生成；`provider-contract/documentation.mjs` 提供方法与字段语义，供 Kotlin KDoc、TS 编辑器注释与参考文档使用。

```powershell
node provider-contract/generate.mjs
node provider-contract/generate.mjs --check
```

## 4. 契约规模

| 项 | 内容 |
|---|---|
| 业务操作 | 161 个 typed 操作 |
| tag 61..126 | 评论删除、私信、一起听房间与同步、MV / 视频、DJ、日历 / 事件、歌单元数据、账号与库操作 |
| tag 127..165 | 扩展搜索、游标评论、独立专辑浏览、通知、排序、导入、二进制上传、视频 / DJ 浏览、最近媒体、账号绑定、音质枚举 |
| tag 166 | 有界喜欢的 ID 分页（`accounts.likes.page`），宿主聚合完整收藏集合 |
| 生成物 | 见 [操作表](../docs/dev/providers/SDK_OPERATIONS.md)、[字段参考](../docs/dev/providers/SDK_TYPES.md)、[分域实现](../docs/dev/providers/WORKFLOWS.md) |
| 入口 | [开发指南](../docs/dev/providers/GUIDE.md) |

`MusicProvider` 继承 `ExtendedOperations`；这些新增能力的 Kotlin 常量在 `ExtendedCapability`。全部新增都有 `Unsupported` 默认实现，既有 Provider 无需实现。

生成器同时维护 JS / JNI 派发、宿主入口、统一层转发与非空跨语言用例，`--check` 覆盖这些文件。既有操作 tag 与记录布局不变。能力数上限为每会话 256 条（原 64 条），Provider 可以声明当前全部契约。

## 5. 数据语义

| 主题 | 约定 |
|---|---|
| 私信 | 正文纯文本，消息页新到旧，游标不透明 |
| 一起听 | 毫秒，含心跳 `intervalMs`；无队列与空队列是两种状态 |
| 歌单标签 | `null` 不改，`[]` 清空 |
| 评论游标 | `CommentQuery` 增加输入游标，不改 `CommentPageRequest` 与其他线布局 |
| 相册 | 浏览、最新发现、日期榜单是独立方法 |
| 搜索 | 含 MV、视频、用户、DJ 电台分页 |
| 上传 | 传字节，不传宿主路径或 base64 封套；图片与音频块上限 1 MiB |
| 云盘上传 | `prepareCloudUpload` → `uploadCloudChunk` → `completeCloudUpload`；Provider 持有上游 token 与会话句柄；块要么整块成功并给出精确下一 offset，要么失败 |
| 上传校验 | Provider 负责会话归属、声明大小、offset 顺序、最后一块边界 |
| 手机绑定密码 | 明文输入，上游哈希由 Provider 负责 |

契约与本地派发测试不代表生产上游支持。剩余移植面见 [迁移状态](../docs/history/SDK_MIGRATION_STATUS.md)。

不要手改生成物。一致性检查：

```powershell
node provider-contract/generate.mjs --check
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
```

归属、ID / 时间 / 批量语义、安全限制与迁移规则见 [GUIDE](../docs/dev/providers/GUIDE.md)。历史迁移记录见 [PROVIDER_TYPED_RUNTIME](../docs/history/PROVIDER_TYPED_RUNTIME.md)。

旧的 `provider-sdk/` TS SDK 仍作为遗留兼容 SDK 存在，未删除，也不会被静默解释成当前契约。

## 相关

- Provider 文档索引：[docs/dev/providers/index](../docs/dev/providers/index.md)
- 契约维护：[provider-contract/README](../provider-contract/README.md)
- 构建与验证：[BUILD](../docs/dev/BUILD.md)
