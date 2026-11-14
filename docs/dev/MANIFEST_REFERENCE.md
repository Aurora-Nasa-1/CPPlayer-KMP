# SDK Provider Manifest 参考

更新：2026-11-10。事实源为 `ModuleManifest`、`ProviderFactory`、`ModuleManager`
与 `verifyRepositoryPackage`。此文替代旧 Provider v1/v3 安装规范。
商店清单与新旧仓库分发规范见 [插件商店分发指南](PLUGIN_STORE_REPO_GUIDE.md)。
**独立 Lyrico 歌词插件不使用此 schema**，见 [歌词插件指南](LYRICS_PLUGIN_DEV_GUIDE.md)。

## 最小清单

ZIP 根目录必须包含 UTF-8 的 `manifest.json`：

```json
{
  "id": "my-provider",
  "name": "My Provider",
  "version": "1.0.0",
  "type": "js",
  "contract": "kmp-sdk-v1",
  "entryPoint": "index.js",
  "capabilities": ["search", "playback"],
  "sdkOrigins": ["https://api.example.com"],
  "settingsSchema": [
    { "key": "origin", "label": "API origin", "type": "string", "default": "https://api.example.com" }
  ]
}
```

## 生效字段

| 字段 | 语义 |
|---|---|
| `id` | 稳定身份；匹配 `[a-z0-9][a-z0-9._-]{0,127}`，不能含 `..` 或等于 `local` |
| `name` | 显示名称，不是更新/账号迁移的身份键 |
| `version` | 插件版本；仓库更新使用严格 SemVer |
| `type` | `js` / `script`，或原生适配器 `jni` / `native` |
| `contract` | 必须精确为 `kmp-sdk-v1`；缺省、旧契约、未知主版本均拒绝 |
| `entryPoint` | 包内相对文件路径；不能为绝对路径、含反斜杠/冒号、空段、`.` 或 `..` 段 |
| `nativeFactoryClass` | 可选自定义 JAR 适配器类；直接安装 DLL / SO / dylib 时不需要 |
| `supportedAbis` | 原生包可选 ABI 列表；声明时必须包含当前平台 ABI |
| `sdkOrigins` | runtime HTTP origin 白名单；省略等于禁止 HTTP，不是“任意域名” |
| `capabilities` | 安装展示标签，按下方规则映射；不能替代运行期方法能力 |
| `settingsSchema` | 声明式设置；会话获得已保存值与默认值组成的只读快照 |
| `qualityOptions` | 业务音质 key 与应用档位的映射 |
| `updateUrl` | 可选更新描述地址 |
| `apiVersion` / `minHostApiVersion` | 既有仓库元数据，缺省 1；正整数，最低宿主要求不能高于当前值 3 |

`apiVersion` 数字 **不决定协议**；写成 1 或 3 都不能开启旧加载路径。
`contract` 才是 SDK 版本闸门。

## 能力与音质

manifest 标签以 `ProviderCapability.sdkLabels` 为准：

| UI 标签 | 清单映射条件 |
|---|---|
| `search` | 标签本身，或 tracks/playlists/albums/artists 的 search 能力之一 |
| `playback` | 标签本身，或 `tracks.resolve` |
| `lyricsSearch` | 标签本身，或 `lyrics.get` |
| `userPlaylists` | 标签本身，或 `playlists.user` |
| `artistExplorer` | 标签本身，或同时声明 `artists.get`、`artists.tracks`、`artists.albums` |
| `albumExplorer` | 标签本身，或 `albums.get` |

运行期仍以 `MusicProvider.capabilities()` / JS `provider.capabilities` 为准，
缺少 runtime 方法能力时请求明确返回 `Unsupported`。

评论、登录、一起听、上传等已经有独立 SDK 方法能力，但不能靠 `comments`、
`qrLogin` 等旧 manifest 标签启用。具体能力名见 [操作参考](SDK_OPERATIONS.md)，
方法参数见 [字段参考](SDK_TYPES.md)。
未知标签忽略，不因新标签导致 JSON 解码失败。

```json
{
  "qualityOptions": [
    { "key": "flac", "label": "FLAC", "targetQuality": "lossless" }
  ]
}
```

宿主把请求档位映射为业务 key；返回时按实际 `actualQuality` 还原标签。
不要在未知情况下把请求档位当作实际音质。

## 安装与更新纪律

1. 入口必须存在于包内，规范化路径不能越过模块根目录；脚本最多 1 MiB、严格 UTF-8，原生入口最多 64 MiB。
2. 手动导入、显式更新、仓库安装共用事务；新包结构验证完成后才备份/替换旧目录。
3. 更新按精确 ID；目标 ID 不同或已安装另一个同名不同 ID 插件时拒绝，不自动删除/迁移。
4. 安装中断的备份在启动时恢复；失败包不应破坏旧安装。
5. 仓库下载在解压前校验外部 SHA-256、字节数、ID/版本/runtime/API 与安全归档路径。
   清单内的“自身 ZIP sha256”不可能自洽，不做这种校验。
6. 活跃音源的仓库更新仍可暂存至重启，避免在途调用持有旧描述符；不再为 DLL 锁实现换 ID 删除链。
7. `getModuleDir`、删除和导出也验证 ID，不能用路径穿越访问模块根目录外的文件。

## 不再生效的旧字段与类型

HTTP/Binary/JNI/FFM 旧包和旧脚本协议均不再安装。
原生 SDK 包使用下方统一 SDK 入口，不要求宿主注册或额外信任授权。
`apiMap`、`loginMethods`、`targetAppPackage` 等旧元数据可能仍能解码，
但不会恢复旧服务、登录工作流或按 ABI 猜 DLL 入口。

不要只给旧包换一个 `contract` 值；必须迁移业务方法与数据类型。
已有用户包文件不自动删除，退役包会给出可解释的拒绝原因。

## 原生 SDK 包

ZIP 示例（桌面 Windows x64）：

```text
manifest.json
my_provider.dll
```

```json
{
  "id": "my-native-provider",
  "name": "My Native Provider",
  "version": "1.0.0",
  "type": "jni",
  "contract": "kmp-sdk-v1",
  "entryPoint": "my_provider.dll",
  "supportedAbis": ["x86_64"]
}
```

`entryPoint` 直接指向包内动态库，也可明确写成 `lib/arm64-v8a/libmy_provider.so`。
无需 `nativeFactoryClass` 或附带 JVM JAR。库导出以下标准 JNI 符号，宿主负责加载桥接类、
类型化 SDK 请求的 Wire 编解码，以及会话的取消、排空与释放：

| 符号前缀 `Java_cp_player_sdk_jni_NativeSession_` | JNI 参数（省略 `JNIEnv*` 和接收者） | 返回值 |
|---|---|---|
| `createNative` | `providerId, accountId, credential, settingsJson`（均为 `jstring`，凭证可空） | 非零 `jlong` 会话句柄 |
| `invokeNative` | `jlong handle, jint operation, jbyteArray request` | `jbyteArray` |
| `cancelNative` | `jlong handle` | `void`；线程安全、立即返回，不释放句柄 |
| `closeNative` | `jlong handle` | `void`；调用排空后释放一次 |

请求是对应 SDK DTO 的 Wire 字节；响应首字节 `0` 表示成功，后接响应 DTO，
`1` 表示失败，后接 `Failure`。**完整字节定义（基本类型、可选/列表、操作码、
`operation = 1` 的能力查询、解码后的宿主校验）见
[原生 Provider 的 Wire 格式](PROVIDER_NATIVE_WIRE.md)** —— 只看本页无法写出可用的库。
每个包的 JNI 绑定独立，不能复用另一音源的句柄或全局账号状态。

> ⚠️ **JNI 模块只在 JVM 运行时可用；GraalVM Native Image 构建（Windows 安装包）需要库额外导出 C ABI v1。**
> 「每个包独立绑定」是靠宿主把 `cp.player.sdk.jni.NativeSession` 按包 `defineClass` 一次实现的：
> 类对象不同，JNI 绑定才不同。而 native image 里**类文件不是可读资源**
> （`getResourceAsStream("...class")` 恒为 `null`），也**不支持运行时定义类**。
> 所以原生构建改按符号直调：库同时导出 `cpp_abi_version` / `cpp_create` / `cpp_invoke` /
> `cpp_cancel` / `cpp_close` 即可（JNI 符号保留，JVM 路径不变）。
> 只导出 JNI 的老模块在原生构建里会明确报错并提示升级，或改用 JS 音源 / JVM 版客户端。
> 完整规范见 [原生 Provider 的 C ABI（v1）](PROVIDER_NATIVE_ABI.md)。

桌面与 Android 发布对应平台动态库；仓库 artifact 的 `runtime` 与 manifest 的 `type` 保持一致。
宿主复制独立运行快照后加载，目录替换不会锁住已安装文件；旧会话排空后释放适配器。
JNI 以应用权限运行，没有额外的信任名单、签名或授权开关。

需要自定义 JVM 桥接时，也可以把 `entryPoint` 设为适配器 JAR，并声明
`nativeFactoryClass` 为 `cp.player.sdk.jni.NativeProviderFactory` 的实现类（公开无参构造器）。
`open(context)` 返回独立的 `JniProviderSession`。桌面使用 JVM JAR，Android 使用含
`classes.dex` 的 DEX JAR；通过 `System.loadLibrary` 加载的库放在 `lib/<ABI>/` 或包根目录。
