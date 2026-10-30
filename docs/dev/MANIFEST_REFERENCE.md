# CPPlayer 插件 Manifest 规范参考（单一事实源）

> 本文是 `manifest.json`（音源插件）的**唯一权威规范**，逐字段对照宿主源码
> （`core/src/commonMain/kotlin/cp/player/core/provider/ModuleManifest.kt`、`ModuleManager.kt`、
> `HttpProvider.kt`、`BinaryProvider.kt`、`JniProvider.kt`、`PlatformSupport.jvm.kt`）。
> 其它指南（QUICK / PROVIDER_DEV_GUIDE）只保留最小示例并链接到这里；两者冲突时以本文为准。
>
> 歌词源插件用的是**另一套 manifest schema**（`versionCode`/`entry`/`capabilities=searchSongs|getLyrics`），
> 见 [LYRICS_PLUGIN_DEV_GUIDE.md](LYRICS_PLUGIN_DEV_GUIDE.md)。

---

## 1. 文件与格式纪律

- `manifest.json` 必须位于 ZIP **根目录**，且是**合法 JSON**（不允许 `//` 注释、尾逗号、单引号）。
  解析失败 = 「导入失败」，错误文案不指向具体字段 —— 落盘前请过一遍 JSON 校验器。
- 模块包是 `.zip`（桌面端导入对话框只过滤 `.zip`；`.cpm` 后缀仅 Android 端经 SAF 可用）。

## 2. 全字段表

| 字段 | 类型 | 必填 | 缺省 | 语义与源码锚点 |
|---|---|---|---|---|
| `id` | String | ✅ | — | 唯一全局标识（如 `netease`、`qqmusic-rs`）。**更新匹配**按同 id 或同 name 判定（`ModuleManager` L134-152）；id 变更会清掉旧模块目录（L169-176） |
| `name` | String | ✅ | — | 显示名（音源管理页、登录页） |
| `version` | String | ✅ | — | 语义化版本号 |
| `type` | String | ✅ | — | `"http"` / `"jni"` / `"binary"`。⚠️ `binary` **仅桌面端**；Android 端自动探测 `.so` 提升为 jni（`ProviderFactory` L31-46），探测不到则拒装 |
| `entryPoint` | String | ✅ | — | http：基地址；jni：库文件名；binary：可执行文件名。jni 加载顺序：`lib/<abi>/<entryPoint>` → 根目录 → Android 单 `.so` 探测（`PlatformSupport` L268-308） |
| `apiMap` | Map<String,String> | 可选 | null | **key = 宿主内部标准方法名**（`cloudsearch`、`song/url/v1`、`lyric/new`…），**value = 你的端点**。查不到时按 key 原样请求（`ProviderManager.callApi` L143）。value 为 `"unsupported"` 时短路返回 `{"code":-1}`。完整方法名清单见 PROVIDER_DEV_GUIDE §7 |
| `apiVersion` | Int | 可选 | **1** | 插件自身实现的插件 API 版本（宿主 `HOST_PROVIDER_API_VERSION = 3`）。**缺省必须是 1**：历史包没这字段，默认成 3 会把老包误判成新格式 |
| `apiVersionTag` | String | 可选 | null | **接口代际标签**（自由字符串，如 `"netease-v3"` / `"v123"`）。**纯展示，不分支行为**：不拼 URL、不切解析、不作闸门，宿主不认识也照常加载。空白串等价于未声明。见 §2.1 |
| `minHostApiVersion` | Int | 可选 | **1** | **加载的唯一闸门**：`> 3` 拒装并提示升级（`ModuleManifest.isLoadable` L206） |
| `capabilities` | Array\<String\> | 可选 | null | 能力声明。语义见 §3 |
| `updateUrl` | String | 可选 | null | 检查更新 JSON 端点（§6） |
| `supportedAbis` | Array\<String\> | 可选 | null | `["arm64-v8a","armeabi-v7a","x86_64"]`；配合 `lib/<abi>/` 目录。null=单架构兼容旧格式 |
| `targetAppPackage` | String | 可选 | null | 目标 App Android 包名（登录页「打开目标 App」）。仅 Android 生效 |
| `loginMethods` | Array\<String\> | 可选 | null | 取值：`qr` / `email` / `sms` / `cookie` / `captchaImage` / `webview`（或 `web`）。null=展示全部常见方式；`cookie` 是宿主兜底、始终可用 |
| `sha256` | String | 可选 | null | 整包（原始 ZIP 字节）SHA-256 十六进制。填了则导入时校验，不匹配拒装。生成：`sha256sum my.zip` |
| `contract` | String | 可选 | null | 标记响应模型风格：`"standard"` / `"netease-legacy"`。**当前是纯元数据**——宿主解析器同时兼容两种形状，无任何代码按它分支；填了不改变任何解析路径 |
| `settingsSchema` | Array\<Object\> | 可选 | null | 声明式设置表单（§5） |

### 2.1 `apiVersionTag` 怎么用（与 `apiVersion` / `contract` 的区别）

三者的分工，**别互相套用**：

| 字段 | 回答的问题 | 宿主行为 |
|---|---|---|
| `apiVersion`（Int） | 「这个包实现了第几代**插件契约**」 | **参与闸门**：与 `minHostApiVersion` 一起决定能否加载 |
| `contract`（String） | 「响应是扁平还是网易云嵌套」 | 纯元数据，解析器两种都认，不分支 |
| `apiVersionTag`（String） | 「这是**哪一套上游接口**」（如网易云 legacy / v3 / `v123`） | **纯展示**：音源管理页与诊断页照原样印出来 |

`apiVersionTag` 明确不做的事（避免有人以为写了它就能改行为）：

- **不拼进请求 URL。** HTTP 音源仍然请求 `${entryPoint}/<method>`。上游接口的版本段
  要拼就把版本写进 `entryPoint` 自己（例如 `"entryPoint": "http://127.0.0.1:3000/v123"`），
  或者用 `apiMap` 把方法映射到带版本段的端点名。
- **不切换解析路径。** 想声明扁平/嵌套用 `contract`（那也还是元数据）。
- **不作加载闸门。** 判据只有 `minHostApiVersion`；新代际名（宿主不认识的串）必须照常加载。
- **不写进枚举白名单。** 任意字符串，宿主只做 `trim()` 后非空判断；空白串 = 未声明。

典型写法（`id` 是模块标识、`apiVersionTag` 是接口代际，两者不必相同）：

```json
{
  "id": "ncm-api-rs",
  "name": "网易云音乐 (v3)",
  "version": "1.0.0",
  "type": "jni",
  "entryPoint": "ncm_api_rs.dll",
  "apiVersionTag": "netease-v3"
}
```

音源管理页副标题会多出一段 ` · netease-v3`（拼在 id 之后、`API vN` 之前），
诊断页「已注册与已加载音源清单」里多一行 `接口代际: netease-v3`。
没声明该字段的包与从前完全一样，不占位、不占行。

## 3. `capabilities` 语义（全部 14 个值）

宿主定义（`ProviderCapability`，未识别的值**忽略不报错**，前向兼容）：

`search` · `playback` · `qrLogin` · `webviewLogin` · `wordSyncedLyrics` · `comments` ·
`cloudDrive` · `recommend` · `lyricsSearch` · `charts` · `userPlaylists` · `artistExplorer` ·
`albumExplorer` · `directoryBrowser`

`isFeatureAvailable(capability)` 的两态语义（`ModuleManifest` L228-229）：

| manifest 状态 | 行为 |
|---|---|
| `capabilities` 字段**不存在**（null，历史包） | **什么都不隐藏**：保守兼容，所有入口照常显示 |
| `capabilities` **存在**（哪怕空数组） | 未声明的能力 → 对应 UI 入口隐藏 |

**当前真正做了 UI 门控的只有 3 个**（app 源码全部消费点）：
- `comments` → 播放页评论入口（`PlayerScreen` / `DesktopPlayerScreen` / `CommentScreenModel`）
- `cloudDrive` → 曲库云盘入口（`CloudDriveScreen` / `LibraryScreen`）
- `recommend` → 首页每日推荐区（`HomeScreen`）

其余 11 个值目前只影响**能力徽标**与「为什么这个按钮没有」的解释文案。
**不要把文档读成「声明了就有一整套页面」** —— 那是远期目标；当前以源码消费点为准。

`loginMethods` 与 `qrLogin`/`webviewLogin` 是两套机制：登录页入口显隐听 `loginMethods`。

## 4. 响应码与错误码

宿主侧「成功」判定唯一码表（`ApiResponseCodes`）：

| 集合 | 码 | 用途 |
|---|---|---|
| `SUCCESS` | `0` / `200` / `201` / `301` | 通用成功（网易云风格 200；0 为部分 Provider；201 创建类；301 重定向类） |
| `QR_PENDING` | `801` / `802` / `803` | 扫码中间态（等待扫码 / 已扫码待确认 / 已确认）——**不是成功**，只在 `login/qr/check` 有意义；`800` = 二维码过期（失败） |
| `VERIFY_REQUIRED` | `250` / `804` / `4001` | 触发安全风控 / 人机核验：宿主弹安全卡片引导完成验证 |

短路/业务错误码：

| 场景 | 形态 |
|---|---|
| apiMap 映射为 `"unsupported"` | `ProviderManager` 直接返回 `{"code":-1,"msg":"该提供商不支持此功能"}`，不触网 |
| HTTP 调用失败 | `{"code":500,"msg":"HTTP call failed: ..."}` |
| 业务层 Unsupported | `MusicSourceFromApi.toMusicResult` 把 `{-1, 501, 404}` 归类为 unsupported（能力缺失，换音源）；其余错误码归为临时失败（重试） |

对外集成 API（第三方 HTTP 契约）的错误码是**另一套**（`IntegrationDto.ApiErrorCodes`），见 [INTEGRATION_API.md](INTEGRATION_API.md) §3，不要与本表混用。

## 5. `settingsSchema` 控件契约

```json
{
  "key": "serverUrl",
  "label": "服务器地址",
  "type": "string",
  "placeholder": "https://…",
  "default": "https://default.example.com",
  "options": [{ "label": "标准", "value": "standard" }],
  "required": true,
  "description": "悬停说明"
}
```

- `type` 取值：`"string"` / `"password"` / `"switch"` / `"select"`（UI 侧 `"switch"` 与 `"boolean"` 都认）。
- ⚠️ **`default` 必须是 JSON 字符串**：schema 定义 `default: String?`，写成布尔 `"default": false`
  会让整个 manifest 的 kotlinx 反序列化直接抛异常 → 「导入失败」。switch 也写 `"true"` / `"false"`。
- ⚠️ `required` 只影响 UI 显示，宿主**不做强制校验**——必填缺值时请求里只是没有该字段。后端自行兜底默认值。

**注入机制**（`HttpProvider.callApi`，JniProvider / BinaryProvider 同理）：
1. HTTP Header：`X-Provider-Config: {"serverUrl":"…",…}`（仅当 schema 存在；值为用户已存值或 `default`，仅非 null 注入）；
2. POST JSON body 根层平铺同名键（**业务参数覆盖同名字段**）；
3. body 里另附 `config: {…}` 一份。
4. 持久化命名空间：`provider_config_<id>`（`defaultSettingsStorage`）——与模块目录解耦，模块更新不丢。

## 6. 更新机制

- `updateUrl` 返回：`{ "version": "1.1.0", "downloadUrl": "https://…/v1.1.0.zip", "changelog": "…" }`。
- **更新 = 先删旧目录、再移入新目录**：包内任何运行时数据子目录一律**不保留**（此前文档声称保留 `user_data/` 是错误的）。用户级持久化走 `settingsSchema` 命名空间。
- 更新匹配：同 `id` 或同 `name`；`id` 变更会清理旧目录。

## 7. 音质档位真相表

| 档位 | 谁在用 | 说明 |
|---|---|---|
| `standard` / `exhigh` / `lossless` / `hires` | 宿主 UI 4 档（`AppModel.qualityLevels`）；默认 **`exhigh`**（播放与下载同） | 请求 `level` 参数即这 4 个值 |
| `lossless` / `hires` / `jymaster` / `sky` | 桌面端 `LOSSLESS_LEVELS`：触发「边播边落盘」（引擎无法定位 FLAC over HTTP） | 插件照常返回直链即可 |
| `higher` / `jymaster` / `sky` / `dolby` | 仅作为透传值出现在 `level` 参数（宿主 UI 不主动请求） | 建议「能处理就处理、处理不了回落最近低档位」，不要报错 |

## 8. 安装与调试

| 项 | 位置/方式 |
|---|---|
| 桌面模块目录 | `~/.cpplayer/modules/` |
| Android 模块目录 | `filesDir/modules/`（经 SAF 导入） |
| 导入失败原因 | 设置 → 音源管理 / 诊断页会显示 `ModuleManager.lastLoadError` 的具体文案（manifest 缺字段、类型不支持、ABI 不匹配、entryPoint 不存在等） |
| JNI 日志 | 日志前缀 `[JniProvider]`（含期望导出符号名，符号不匹配时据此定位） |
| 健康监控 | 设置 → 调试 → 健康状态（按方法统计调用、期望字段命中、响应耗时） |
| 通用调试 | `apiMap` 键必须等于宿主方法名；manifest 必须是合法 JSON；`required` 不强制 |

## 9. 与音源插件 apiVersion 的关系

音源插件走本文 `apiVersion`（当前宿主支持 3）；**歌词源插件**走独立的
`LyricsPluginManifest.pluginApiVersion`（`core/.../lyricsplugin/`）——两套编号互不通用，见歌词插件指南。
