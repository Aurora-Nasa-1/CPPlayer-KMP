# Provider 契约

> 实现哪些方法、返回什么、宿主替你做了什么。适用读者：全部 Provider 作者。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-15 · 事实源 `provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/`

`v1` 是 SDK 契约主版本，不是已退役的 Legacy Provider v1 或 Standard v3。

## 1. 只实现业务

| 你写 | 宿主写 |
|---|---|
| `search` / `get` / `getMany` / `resolve` 等业务方法 | 会话创建、取消、超时、能力校验 |
| 解析上游 JSON | origin 白名单、HTTP 转发 |
| 返回 DTO | 安装、更新、回滚、加载 |
| 声明 `capabilities` | 决定请求是否下发 |

不需要写：`callApi`、`apiMap`、本地 HTTP 服务、JNI 服务端入口、网易云兼容响应、`{code, data}` 包装。解析自家上游 JSON 属于正常业务；把上游格式当宿主协议返回不允许。

## 2. 核心方法

| 方法 | 能力名 | 返回值 |
|---|---|---|
| `capabilities()` | 无 | `Capabilities`，会话内不可变 |
| `search(SearchRequest)` | `tracks.search` | `TrackPage` |
| `get(IdRequest)` | `tracks.get` | `Track` |
| `getMany(BatchRequest)` | `tracks.getMany`，或不声明而用默认逐项实现 | `TrackBatch` |
| `resolve(ResolveRequest)` | `tracks.resolve` | `MediaResource` |
| `resolveDrm(DrmResolveRequest)` | `tracks.resolveDrm`（tag 168） | `ResolvedMedia` |
| `exchangeLicense(LicenseRequest)` | `tracks.exchangeLicense`（tag 169） | `LicenseResponse` |
| `close()` | 无 | 释放会话资源 |

已支持只读目录：远端歌词、歌单（搜索 / 详情 / 曲目 / 用户歌单）、专辑（搜索 / 详情）、歌手（搜索 / 详情 / 曲目 / 专辑）。

扩展业务已接入登录、推荐、评论/私信、一起听、MV/视频、DJ、日历/动态、账号/云盘、目录写入与 DRM，共 164 个操作。签名与能力名见 [SDK_OPERATIONS](SDK_OPERATIONS.md)。

| 规则 | 说明 |
|---|---|
| 默认实现 | 未实现方法返回 `Unsupported`；新增能力必须保留该默认 |
| 常量位置 | 原有能力在 `Capability`，扩展能力在 `ExtendedCapability` |
| 声明与实现 | 只声明能力而留空实现视为未实现 |
| 上游真实状态 | 契约可调用不等于某个音源已完成上游实现或真机联调 |

扩展方法的请求对象由 `UnifiedMusicSource` 按当前账号会话调用，内部 ID 用 Provider 原始字符串，不要混入 `CPMediaId`。面向命名空间 ID 的视频入口是 `resolveVideoResource`，它先核对 Provider。

| 字段 / 状态 | 约束 |
|---|---|
| 私信正文 | 纯文本，不是 JSON |
| 游标 | 不解析、不转换、原样回传 |
| 消息页顺序 | 新到旧 |
| 一起听时间 | 全部毫秒 |
| 队列快照 | 空队列与尚无队列是两种状态 |
| `tags = null` / `tags = []` | 不改 / 清空 |

## 3. Kotlin

仓库内依赖 `project(":provider-sdk-kmp")`。版本 `0.1.0` 是本地预览，不是已发布的 Maven 坐标；目标为 Android / JVM，Kotlin 二进制包不能当插件 ZIP 安装。

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

| 项 | 说明 |
|---|---|
| `catalog` | 音源自己的业务实现 |
| 可编译示例 | [`examples/kotlin/CatalogProvider.kt`](../../../provider-sdk-kmp/examples/kotlin/CatalogProvider.kt) |
| 注册方式 | 宿主通过 `SdkBackendProvider` 注册 `(ProviderContext) -> MusicProvider` 工厂 |
| 注册归属 | 宿主装配代码，不是作者必须重复实现的接口 |

## 4. 数据纪律

| 规则 | 具体要求 |
|---|---|
| ID | 不透明、区分大小写；保留前导零、逗号、UUID；不转数字、不拆 CSV |
| 宿主侧 ID | 统一层用 `{providerId}://song/{id}`；SDK 只收裸资源 ID |
| 时间 | `durationMs` 为毫秒，`9500` 就是 9.5 秒，不按数值猜秒/毫秒 |
| 分页总数 | `TrackPage.total`、`hasMore` 可空；未知即 null，不用当前页长度伪造 |
| 批量 | 输出与输入逐项对应，保序、保留重复；每项恰好有 `track` 或 `failure` |
| 音质 | 返回真实 `actualQuality`；格式、码率、大小、到期时间未知时留空 |
| 媒体头 | 只用 `MediaResource.headers` 显式声明的头 |
| Cookie | 宿主不把账号 Cookie 加到 CDN、下载、预加载或中继请求 |

## 5. 会话、账号与错误

每个实例属于不可变的 Provider / 账号 / 设置 / 凭证版本会话。

| 事件 | 宿主行为 |
|---|---|
| 切换 Provider、选账号、改凭证或配置 | 取消在途调用 → 排空 → 释放适配器 → 新建会话 |
| 旧会话返回结果 | 丢弃，不能写回 |
| `capabilities()` | 每会话只读一次；失败或取消不缓存 |
| 凭据保存或清除 | 立即取消对应活跃账号的请求，其他账号不受影响 |

登录、凭证和账号身份的完整约定见 [登录与账号](WORKFLOWS.md#10-登录与账号)。`context.credential` 是当前会话的凭证快照；`getCurrentAccount` 报告凭证是否有效。账号身份必须能从凭证或上游恢复，不能只放在旧会话的内存里。

错误用统一类别表达。Kotlin 抛 `ProviderException(Failure(category, message))`，JS 抛带 `category` 的 `Error`。

| 类别 | 使用场景 |
|---|---|
| `Unsupported` | 未实现或上游不支持 |
| `NotFound` | 资源不存在 |
| `Authentication` | 未登录、凭证失效 |
| `Network` | 连接失败、上游 5xx |
| `RateLimit` | 触发限流 |
| `Timeout` | 超时 |
| `InvalidResponse` | 上游返回无法解析或不合约束 |
| `Runtime` | 其他实现内错误 |

取消必须原样传播，不能吞成空数据。错误文本不得包含凭证、私有 URL 或请求体。

## 6. DRM 播放（可选）

DRM 由两个独立能力组成，**必须成对声明**；只声明一个时宿主按未实现处理。

| 方法 | 能力 | 输入 → 输出 | 责任 |
|---|---|---|---|
| `resolveDrm` | `tracks.resolveDrm` | `DrmResolveRequest` → `ResolvedMedia` | 解析加密资源与 HLS/信令描述，不生成许可证 |
| `exchangeLicense` | `tracks.exchangeLicense` | `LicenseRequest` → `LicenseResponse` | 把宿主 CDM 的 challenge 换成 license |

| 规则 | 内容 |
|---|---|
| 描述边界 | `DrmDescriptor` 只含信令（`keySystem`、`schemeIdUri`、`initData`、`keyId`、`keyUri`、`serverCertificate`、`licenseUrl`）；绝不含 content key 或可复用 license |
| `licenseUrl = null` | 宿主**必须**调用 `exchangeLicense`；非空表示平台可直接 POST challenge |
| 敏感字节 | challenge 与 license 只在内存中传递，不写日志、不缓存、不持久化、不进错误消息 |
| 真实音质 | `actualQuality` 按实际选中的 HLS variant 返回，不能照抄请求档位 |
| 不可用即失败 | 宿主请求的 key system 在资源里不存在时返回 `Unsupported`，不能返回一个必然播不了的加密 URL |
| 平台矩阵 | Android：Widevine（Media3；Flick 模式强制切 Media3）；Windows 桌面：Widevine（WebView EME 桥，见 [DRM_VERIFICATION](DRM_VERIFICATION.md)）；FairPlay：仅 Apple 平台 |
| 回退顺序 | DRM → 明文 preview（`resolve`）→ `Unsupported` |
| 禁用路径 | DRM 流不进入磁盘流缓存、`StreamLocalizer`、`DownloadEngine`、`LocalServer` 导出 |

宿主只在 `supportedDrmKeySystems()` 非空且 Provider 同时声明上述两个能力时调用 `resolveDrm`；返回 `Unsupported` 后回退旧的 `resolve`。Provider 不应自行做 CDM、解密或把 license 写入文件。

### 6.1 桌面 WebView EME 桥

Windows 没有面向普通桌面应用的 Widevine 系统接口，宿主改为借浏览器内核解密：

| 环节 | 实现 |
|---|---|
| 能力探测 | `supportedDrmKeySystems()` 仅在发现 Edge / Chrome / WebView2 Runtime 内核时返回 `widevine` |
| 启动 | `msedge.exe` / `chrome.exe` 以 `--app` 离屏窗口加载本地 `player.html` |
| 清单 | 本地代理把 Apple `METHOD=ISO-23001-7` 归一化为 `SAMPLE-AES-CTR`（hls.js 只认后者），并把 variant / audio group 继续代理化 |
| license | 播放页 POST challenge → 宿主 `exchangeLicense` → Provider；license 字节原样回传给 CDM |
| 安全 | 桥只绑 `127.0.0.1`，一次性 token 校验；challenge / license 不落日志与磁盘 |

此时 Provider 仍然完全平台无关：它不需要知道解密由 Media3 还是浏览器内核完成。
接口细节见 [DRM_VERIFICATION](DRM_VERIFICATION.md) 与 [SDK_TYPES](SDK_TYPES.md)。

Android 走同一份 `HlsPlaylistNormalizer`（位于 commonMain）：Media3 1.11 的
解析器同样只接受 `SAMPLE-AES` 系列，所以 `loadDrm` 会在设备上起一个只绑
`127.0.0.1` 的清单代理，再交给 ExoPlayer。两端的差异只在「谁持有 CDM」，
Provider 协议与清单归一化行为完全一致。

## 7. 宿主边界

`ProviderContext` 只提供 Provider / 账号标识、设置快照、凭证与受控 runtime。

| 能力 | 边界 |
|---|---|
| HTTP | 必须命中 `sdkOrigins` origin 白名单；不自动注入 Cookie、不自动跟随重定向 |
| 协议 | HTTPS 可用；明文 HTTP 仅接受 loopback |
| 存储 | 有界会话内临时存储，不是持久化数据库 |
| 文件 / 数据库 / UI | 无权限 |
| JNI | 只承载业务 DTO，不得恢复旧 JSON endpoint 映射 |

| 限制 | 值 |
|---|---|
| 宿主请求截止 | 15 秒 |
| 并发上限 | 每个账号会话 8 个 SDK 调用 |
| 批次 / 分页 | 500 项 |
| QuickJS | 单会话线程、32 MiB 内存、512 KiB 栈、250 ms 执行预算 |

这些是宿主责任，作者不需要自己实现调度器。

## 8. 安装与验证

ZIP 安装支持 SDK JavaScript 与 JNI / Native 包。原生库走宿主统一 `NativeSession` 入口，无需 `nativeFactoryClass`、预注册或额外授权；布局见 [MANIFEST](MANIFEST.md#5-原生-sdk-包)。

| ABI | 符号前缀 | 可用运行时 |
|---|---|---|
| JNI | `Java_cp_player_sdk_jni_NativeSession_*` | JVM 桌面、Android |
| C ABI v1 | `cpp_*` | GraalVM Native Image（Windows 安装包） |

建议同时导出两套：JVM 行为不变，原生构建才有路可走。规范见 [NATIVE](NATIVE.md)。

手动导入与仓库安装都按精确 ID 校验；同名不同 ID 不覆盖。入口缺失、越界、超限、未知契约在替换文件前拒绝；更新失败保留旧安装。

更新清单可声明 `minUsableVersion`，表示低于该版本的音源已经停用、可能无法搜索或播放。宿主检查到当前版本低于停用线时会进入 `UpdateRequired` 状态并弹出强提醒，而不是只显示普通更新角标；字段格式、自洽要求与各端表现为 [MANIFEST 4.1](MANIFEST.md#41-更新清单与最低可用版本)。未声明该字段时维持普通更新流程。

```powershell
# SDK 契约与静态类型校验
node provider-contract/generate.mjs --check
node --test provider-sdk-kmp/js/test.mjs provider-contract/documentation.test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
pwsh -File scripts/verify-provider-chain.ps1

# 音源包打包校验与宿主链路一致性冒烟
node provider-contract/pack.mjs <音源目录> -o dist/<产物.zip>
.\gradlew.bat :core:providerConformance -PproviderPackage=dist/<产物.zip> -PproviderArgs="--json"
```

| 命令 | 检查内容 |
|---|---|
| `generate.mjs --check` | 字段参考、操作参考、Kotlin / TS 注释、分发代码与 schema 一致 |
| `node --test` | JS 契约测试与文档一致性 |
| `check:types` | 生成类型可被 `tsc` 接受 |
| `verify-provider-chain.ps1` | 独立构建目录，XML 的失败 / 错误 / 跳过全为 0 |
| `pack.mjs` | 打包前复核 manifest、入口 UTF-8、体积上限与原生动态库格式 |
| `:core:providerConformance` | 走真实 `ModuleManager` 与 `ProviderHost` 链路冒烟核心能力与异常路径 |

最后一条仅 Windows（调用 `gradlew.bat`）。TS 命令使用 SDK 锁定的开发依赖；独立音源项目用自己的 `tsc` 与包导出类型即可。

修改契约时：结构改 `schema.mjs`，语义改 `documentation.mjs`，再运行生成器。生成结果不能手工改成另一套声明。

## 9. 旧实现迁移

| 步骤 | 动作 |
|---|---|
| 1 | 从旧实现提取上游业务，重写为 `search` / `get` / `resolve` |
| 2 | 删除端点映射、JSON 宿主封套、端口分配、服务启动逻辑 |
| 3 | 账号凭证改为会话上下文；媒体访问凭证放进显式流头 |
| 4 | 按新清单打包并验证 |

不能仅把旧包的 `contract` 改名。生产加载器不接受 HTTP / Binary / JNI / FFM 旧包或旧 JS 协议；既有用户文件不会自动删除、转换或按同名迁移账号。旧 `provider-sdk/`、早期设计记录、未完成工作副本中的兼容代码不是现行契约。

## 相关

- 最短路：[QUICKSTART](QUICKSTART.md)
- 清单字段：[MANIFEST](MANIFEST.md)
- 原生库：[NATIVE](NATIVE.md)
- 分域做法：[WORKFLOWS](WORKFLOWS.md)
- 操作与字段：[SDK_OPERATIONS](SDK_OPERATIONS.md)、[SDK_TYPES](SDK_TYPES.md)
