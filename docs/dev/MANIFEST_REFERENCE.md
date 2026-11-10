# SDK Provider Manifest 参考

更新：2026-11-10。事实源为 `ModuleManifest`、`ProviderFactory`、`ModuleManager`
与 `verifyRepositoryPackage`。此文替代旧 Provider v1/v3 安装规范。
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
| `type` | 仅 `js` 或同义的 `script` |
| `contract` | 必须精确为 `kmp-sdk-v1`；缺省、旧契约、未知主版本均拒绝 |
| `entryPoint` | 包内相对文件路径；不能为绝对路径、含反斜杠/冒号、空段、`.` 或 `..` 段 |
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

1. 入口必须存在于包内，规范化路径不能越过模块根目录；最多 1 MiB，严格 UTF-8。
2. 手动导入、显式更新、仓库安装共用事务；新包结构验证完成后才备份/替换旧目录。
3. 更新按精确 ID；目标 ID 不同或已安装另一个同名不同 ID 插件时拒绝，不自动删除/迁移。
4. 安装中断的备份在启动时恢复；失败包不应破坏旧安装。
5. 仓库下载在解压前校验外部 SHA-256、字节数、ID/版本/runtime/API 与安全归档路径。
   清单内的“自身 ZIP sha256”不可能自洽，不做这种校验。
6. 活跃音源的仓库更新仍可暂存至重启，避免在途调用持有旧描述符；不再为 DLL 锁实现换 ID 删除链。
7. `getModuleDir`、删除和导出也验证 ID，不能用路径穿越访问模块根目录外的文件。

## 不再生效的旧字段与类型

HTTP/Binary/JNI/FFM 旧包和旧脚本协议均不再安装。
可信 Kotlin/Native SDK 工厂只能由宿主代码注册，不通过 ZIP 放开执行信任。
`apiMap`、`supportedAbis`、`loginMethods`、`targetAppPackage` 等旧元数据可能仍能解码，
但不会恢复旧服务、登录工作流或按 ABI 猜 DLL 入口。

不要只给旧包换一个 `contract` 值；必须迁移业务方法与数据类型。
已有用户包文件不自动删除，退役包会给出可解释的拒绝原因。
