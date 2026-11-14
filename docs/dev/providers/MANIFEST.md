# Manifest 与分发

> 安装包结构、`manifest.json` 字段、安装更新事务、商店仓库规范。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-14 · 事实源 `ModuleManifest`、`ProviderFactory`、`ModuleManager`、`verifyRepositoryPackage`

独立歌词插件不使用此 schema，见 [歌词源插件](../lyrics/LYRICS_PLUGIN.md)。

## 1. 最小清单

ZIP 根目录必须包含 UTF-8 的 `manifest.json`。

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

## 2. 生效字段

| 字段 | 语义 |
|---|---|
| `id` | 稳定身份；匹配 `[a-z0-9][a-z0-9._-]{0,127}`，不能含 `..` 或等于 `local` |
| `name` | 显示名称，不是更新或账号迁移的身份键 |
| `version` | 插件版本；仓库更新使用严格 SemVer |
| `type` | `js` / `script`，或原生适配器 `jni` / `native` |
| `contract` | 必须精确为 `kmp-sdk-v1`；缺省、旧契约、未知主版本均拒绝 |
| `entryPoint` | 包内相对路径；不能是绝对路径，不能含反斜杠、冒号、空段、`.` 或 `..` 段 |
| `nativeFactoryClass` | 可选自定义 JAR 适配器类；直接安装 DLL / SO / dylib 时不需要 |
| `supportedAbis` | 原生包可选 ABI 列表；声明时必须包含当前平台 ABI |
| `sdkOrigins` | runtime HTTP origin 白名单；省略等于禁止 HTTP，不是任意域名 |
| `capabilities` | 安装展示标签，按下节规则映射；不能替代运行期方法能力 |
| `settingsSchema` | 声明式设置；会话拿到已保存值与默认值组成的只读快照 |
| `qualityOptions` | 业务音质 key 与应用档位的映射 |
| `updateUrl` | 可选更新描述地址 |
| `apiVersion` / `minHostApiVersion` | 既有仓库元数据，缺省 1；正整数；`minHostApiVersion` 不能高于当前宿主值 3 |

`apiVersion` 数字不决定协议。写成 1 或 3 都不能开启旧加载路径；`contract` 才是版本闸门。

## 3. 能力与音质

manifest 标签以 `ProviderCapability.sdkLabels` 为准。

| UI 标签 | 映射条件 |
|---|---|
| `search` | 标签本身，或 tracks / playlists / albums / artists 的 search 能力之一 |
| `playback` | 标签本身，或 `tracks.resolve` |
| `lyricsSearch` | 标签本身，或 `lyrics.get` |
| `userPlaylists` | 标签本身，或 `playlists.user` |
| `artistExplorer` | 标签本身，或同时声明 `artists.get`、`artists.tracks`、`artists.albums` |
| `albumExplorer` | 标签本身，或 `albums.get` |

| 规则 | 说明 |
|---|---|
| 运行期权威 | `MusicProvider.capabilities()` / JS `provider.capabilities` |
| 缺方法能力 | 请求明确返回 `Unsupported` |
| 未知标签 | 忽略，不导致解码失败 |
| 旧标签 | `comments`、`qrLogin` 等不能启用对应功能，须改用 SDK 能力名 |

```json
{
  "qualityOptions": [
    { "key": "flac", "label": "FLAC", "targetQuality": "lossless" }
  ]
}
```

宿主把请求档位映射为业务 key，返回时按实际 `actualQuality` 还原标签。不要在未知情况下把请求档位当作实际音质。

## 4. 安装与更新纪律

| 规则 | 内容 |
|---|---|
| 入口存在性 | 必须在包内，规范化路径不能越过模块根目录 |
| 大小上限 | 脚本 1 MiB，原生入口 64 MiB |
| 编码 | 严格 UTF-8 |
| 事务 | 手动导入、显式更新、仓库安装共用；结构验证通过后才备份 / 替换旧目录 |
| 身份 | 按精确 ID 更新；目标 ID 不同或已装同名不同 ID 时拒绝，不自动删除或迁移 |
| 中断恢复 | 启动时恢复中断安装的备份；失败包不破坏旧安装 |
| 仓库下载 | 解压前校验外部 SHA-256、字节数、ID / 版本 / runtime / API 与安全归档路径 |
| 清单自校验 | 不做「清单内自身 ZIP sha256」校验，它不可能自洽 |
| 活跃音源更新 | 可暂存至重启，避免在途调用持有旧描述符；不为 DLL 锁实现换 ID 删除链 |
| 路径接口 | `getModuleDir`、删除、导出也验证 ID，禁止路径穿越 |

## 5. 原生 SDK 包

桌面 Windows x64 示例。

```text
my-provider.zip
├── manifest.json
└── my_provider.dll
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

`entryPoint` 直接指向包内动态库，也可写成 `lib/arm64-v8a/libmy_provider.so`。无需 `nativeFactoryClass` 或附带 JVM JAR。

| 符号前缀 `Java_cp_player_sdk_jni_NativeSession_` | 参数（省略 `JNIEnv*` 与接收者） | 返回 |
|---|---|---|
| `createNative` | `providerId, accountId, credential, settingsJson`（`jstring`，凭证可空） | 非零 `jlong` 句柄 |
| `invokeNative` | `jlong handle, jint operation, jbyteArray request` | `jbyteArray` |
| `cancelNative` | `jlong handle` | `void`；线程安全、立即返回，不释放句柄 |
| `closeNative` | `jlong handle` | `void`；排空后释放一次 |

请求是 SDK DTO 的 Wire 字节；响应首字节 `0` 成功、`1` 失败接 `Failure`。字节定义、操作码与解码后校验见 [NATIVE](NATIVE.md) —— 只看本节写不出可用库。

| 规则 | 说明 |
|---|---|
| 绑定隔离 | 每包 JNI 绑定独立，不能复用其他音源的句柄或全局账号状态 |
| 加载方式 | 宿主复制独立快照后加载，目录替换不锁已安装文件 |
| 旧会话 | 排空后释放适配器 |
| 权限 | 以应用权限运行，无额外信任名单、签名或授权开关 |
| 发布 | 桌面与 Android 各发对应平台库；仓库 artifact 的 `runtime` 与 `type` 一致 |

JNI 只在 JVM 可用。GraalVM Native Image 构建（Windows 安装包）要求库额外导出 C ABI v1：`cpp_abi_version` / `cpp_create` / `cpp_invoke` / `cpp_cancel` / `cpp_close`。JNI 符号保留，JVM 路径不变；只导出 JNI 的老包在原生构建里会明确报错并提示升级。原因与规范见 [NATIVE](NATIVE.md#4-c-abi-v1)。

需要自定义 JVM 桥接时，可把 `entryPoint` 设为适配器 JAR，并声明 `nativeFactoryClass` 为实现 `cp.player.sdk.jni.NativeProviderFactory` 的类（公开无参构造器），`open(context)` 返回独立的 `JniProviderSession`。桌面用 JVM JAR，Android 用含 `classes.dex` 的 DEX JAR；`System.loadLibrary` 加载的库放 `lib/<ABI>/` 或包根目录。

## 6. 商店仓库分发

两套仓库物理隔离，保证旧客户端仍能下载 v1/v3 插件，新客户端只拿 `kmp-sdk-v1` 插件。

```text
老版本客户端（已发行，URL 固化）
  └─ https://raw.githubusercontent.com/Aurora-Nasa-1/CPPlayer-Plugins/main/index.json
       └─ Legacy v1/v3（runtime: jni / http / binary）

新版本客户端（KMP SDK）
  └─ https://raw.githubusercontent.com/Aurora-Nasa-1/CPPlayer-Plugins-KMP/main/index.json
       └─ kmp-sdk-v1（runtime: js / script / jni / native）
```

客户端无感迁移（`PluginRepositoryService`）：

| 情形 | 行为 |
|---|---|
| 本地保存旧官方源 URL | 自动换成新官方源并持久化回写 |
| 用户自建第三方源 | 不受影响 |
| 新安装用户 | 直接用新仓库默认源 |

仓库组织：`CPPlayer-Plugins-KMP` 只托管 `kmp-sdk-v1` 插件。目录中的 `version`、`apiVersion`、`minHostApiVersion`、`runtime` 必须与包内 `manifest.json` 逐项一致；缺省按 1 处理。旧仓库 `CPPlayer-Plugins` 保持网易云 1.1.1，不随新版本升级。

```text
CPPlayer-Plugins-KMP/
├── .github/workflows/validate.yml   # 校验目录，push 时核对已发布 ZIP
├── scripts/validate_catalog.py
├── LICENSE
├── index.json                       # 商店目录清单
└── README.md
```

`index.json` 实例（网易云 1.2.2，已验证）：

```json
{
  "schemaVersion": 1,
  "name": "CPPlayer Official Plugins (KMP)",
  "plugins": [
    {
      "id": "netease-cloudmusic-rs",
      "kind": "provider",
      "name": "NeteaseCloudMusicApi-RS",
      "version": "1.2.2",
      "description": "基于 KMP SDK v1 重构的网易云原生音源，支持 161 项业务操作、动态音质与 Native 会话。",
      "author": "Aurora-Nasa-1",
      "homepage": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti",
      "changelog": "v1.2.2: 修复 SDK v1 下每日推荐、私人 FM 与心动模式不可用的问题，补全推荐能力声明并严格限制返回数量。",
      "apiVersion": 1,
      "minHostApiVersion": 1,
      "artifacts": [
        {
          "platform": "windows",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-windows-1.2.2.zip",
          "sha256": "9b3c58b9ea15b52a85eb37f3c63043d0f2db2015b4cf4add5a54802a26a9868e",
          "bytes": 9296667
        },
        {
          "platform": "linux",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-linux-1.2.2.zip",
          "sha256": "080595b6cd20654ed3ccd49116421ddf2f7a54ada92b2e568037ca02399ea00c",
          "bytes": 10040572
        },
        {
          "platform": "android",
          "arch": "arm64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        },
        {
          "platform": "android",
          "arch": "armv7",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        },
        {
          "platform": "android",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        }
      ]
    }
  ]
}
```

| 字段 | 约束 |
|---|---|
| `runtime` | JS 用 `js` / `script`；原生用 `jni` / `native`，且与 manifest 的 `type` 一致 |
| `platform` / `arch` | JS 可写 `any`；原生必须写确切平台与架构 |
| `sha256` / `bytes` | 对应 ZIP 的实际外部校验值 |
| `minHostApiVersion` | 与包内 manifest 一致；未声明时填 1，不按宿主上限补成 3 |

插件 ZIP 结构：

```text
netease-1.0.0.zip
├── manifest.json    # contract: "kmp-sdk-v1"、type、sdkOrigins
└── index.js         # JS 包；原生包此处为 entryPoint 指向的库
```

原生包的库放根目录或 `lib/<abi>/`。

打包脚本（在插件仓库内）：

```javascript
// scripts/pack.mjs
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import archiver from 'archiver';

// 1. 读取 manifest.json，校验 contract === 'kmp-sdk-v1'
// 2. 打包生成 dist/<id>-<version>.zip
// 3. 计算 sha256 与 bytes
// 4. 更新或输出 index.json 条目
```

## 7. 不再生效的旧字段与类型

| 项 | 现状 |
|---|---|
| HTTP / Binary / JNI / FFM 旧包 | 不再安装 |
| 旧脚本协议 | 不再加载 |
| `apiMap`、`loginMethods`、`targetAppPackage` | 可能仍能解码，但不恢复旧服务、登录流程或按 ABI 猜入口 |
| 换 `contract` 值的旧包 | 拒绝；必须迁移方法与数据类型 |
| 已有用户包文件 | 不自动删除；退役包给出可解释的拒绝原因 |

## 相关

- 契约与边界：[GUIDE](GUIDE.md)
- 原生库符号与字节格式：[NATIVE](NATIVE.md)
- 最短路：[QUICKSTART](QUICKSTART.md)
