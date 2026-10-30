# 插件中心与仓库协议 v1

实现日期：2026-10-30。本文描述实际实现，不再引入独立商店后端、账号体系或签名平台。

## 1. 使用入口

- 设置 → 账号与音源 → 插件中心；音源管理、歌词来源也提供入口。
- 四个页签：浏览、已安装、更新、仓库。
- 浏览支持本地搜索和音源 / 歌词筛选；详情展示来源、版本、包大小、SHA-256 和更新说明。
- 安装 / 更新必须确认来源与风险；支持下载进度和取消。提交本地文件期间不允许取消。
- 已安装展示本地导入与内置插件，即使对应仓库已移除，也仍可进入原管理页。
- 启用 / 排序 / 删除继续由音源管理与歌词来源负责，不在插件中心复制一套管理逻辑。

## 2. 联网规则

1. 仓库联网默认关闭；开启后，用户可手动检查目录。添加仓库本身不请求网络。
2. 自动检查单独开关，默认关闭；应用运行时每小时检查调度，每 24 小时最多访问一次目录。
   启动后首次调度延迟 60 秒，不是后台常驻系统服务。失败检查也计入间隔，手动检查可重试。
3. 自动检查只拉目录，不下载 ZIP，不安装、不切换音源。
4. 关闭联网会取消目录请求与下载；已经开始的本地安装事务会完成，避免半安装。
5. 仅请求启用源的 HTTPS 目录及用户所选 ZIP。不发送账号、Cookie、令牌或曲库。
   接收方仍能看到 IP、User-Agent、正常 HTTPS 请求信息。
6. 目录失败保留缓存并明确报错；缓存没有更新不能当作“已经检查且没有更新”。
7. 删除仓库不卸载插件；停用 / 删除仓库后不再从该地址检查更新。
8. 仓库开关只约束分发链路，不是插件运行时的联网防火墙。启用插件后的访问遵循现有宿主行为。

## 3. 目录格式

一个 UTF-8 JSON 即可，适合 GitHub Raw、静态站点或自建 HTTPS 服务。
下面是**格式示意**，地址和哈希不是可安装产物：

```json
{
  "schemaVersion": 1,
  "name": "Example plugins",
  "plugins": [
    {
      "id": "example-lyrics",
      "kind": "lyrics",
      "name": "Example lyrics",
      "version": "1.2.0",
      "versionCode": 12,
      "author": "Example",
      "description": "Example description",
      "changelog": "Fix search",
      "apiVersion": 3,
      "minHostApiVersion": 1,
      "artifacts": [
        {
          "platform": "any",
          "arch": "any",
          "runtime": "rhino-js",
          "url": "https://example.com/releases/example-lyrics-1.2.0.zip",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "bytes": 12345
        }
      ]
    }
  ]
}
```

| 字段 | 约束 |
|---|---|
| `schemaVersion` | 当前只接受 `1` |
| `kind` | `provider` 或 `lyrics`；身份为 `kind:id` |
| `id` | 小写字母、数字、`.`、`_`、`-`，首字符为字母数字；最多 128 字符；禁止 `..`、尾点和 Windows 保留名 |
| `version` | 严格 SemVer；Provider 用它比较更新，构建元数据不影响排序 |
| `versionCode` | 歌词插件必填正整数，更新比较只用此字段；必须与包内一致 |
| `apiVersion` / `minHostApiVersion` | 默认 `1`；音源和歌词是两套独立 API 编号，不能相互套用 |
| `artifacts` | 每插件 1–32 个，当前平台选择最具体项；同优先级出现多个匹配时拒绝 |
| `platform` | `any`、`windows`、`linux`、`macos`、`android` |
| `arch` | `any`、`x64`、`arm64`、`armv7` |
| `runtime` | Provider：`http`、`jni`、`binary`；歌词：`rhino-js` |
| `url` | HTTPS，禁止凭据和片段；最多 5 次重定向，禁止降级到 HTTP |
| `sha256` / `bytes` | 整个 ZIP 的小写 64 位十六进制摘要、精确字节长度；均在包外计算 |

原生包必须明确平台和架构，Android 不接受独立 `binary` 可执行文件。
HTTP / Rhino 包可用 `any`。目录最多 5 MiB、1000 插件；单 ZIP 最多 256 MiB。
同目录不能重复 `kind:id`。未知字段可忽略，未知 schemaVersion 不可忽略。

## 4. ZIP 与安装规则

- 必须是**单插件** ZIP，根目录有且只有一个 `manifest.json`，不支持外套目录或嵌套插件。
- 保留现有 Provider / Lyrico 两套 manifest；ID、版本、API 与目录声明必须逐项一致。
- Provider 的 `type` 必须与所选 artifact.runtime 一致；HTTP 服务入口允许已有 HTTP / HTTPS 行为，
  这与分发 URL 强制 HTTPS 是两回事。
- 在线原生包入口必须为实际文件名，在根目录或 `lib/<abi>/` 存在，
  不依赖旧导入器的后缀 / 文件名猜测。ABI 对应 `x86_64`、`arm64-v8a`、`armeabi-v7a`。
- 歌词包必须有实际入口脚本；include 路径需安全，内置歌词插件不可覆盖。
- 解包前验证长度、SHA-256、清单一致性和 ZIP 全量路径。
  禁止路径穿越、反斜杠、盘符、ADS、保留名、大小写重复路径；
  限制文件数量、单文件与总解压体积。
- Provider 解压上限：4096 条目、单文件 256 MiB、总量 512 MiB。
  歌词：512 条目、单文件 16 MiB、总量 64 MiB。manifest 最大 256 KiB。
- 先暂存再替换，旧目录留作备份；新版本加载失败恢复旧目录和注册信息。
  中断遗留备份会在启动时恢复；已经提交的旧目录不再当作恢复候选。
- 已加载原生 Provider 的更新只暂存 ZIP + 摘要，重启时先验证、加载再提交；
  失败保留旧版本并展示错误，不能把“已下载”等同于“已更新”。
- HTTP 更新当前活跃音源前必须暂停播放。没有当前音源时，安装后按现有规则自动选择。
- 歌词新安装默认停用；更新保留配置与开关，并在专用 dispatcher 上使旧 Rhino 实例失效。

## 5. 来源与信任边界

- 更新绑定完整目录 URL；不同仓库即使同 ID，也不能接管已安装插件。
- 不能按显示名称替换不同 ID；内置插件随应用更新。
- 本地导入的同 ID 插件需额外确认替换，且只允许更高版本；无法解析的旧 Provider 版本不猜测更新。
- 删除插件后可以重新选择分发源。仓库改 URL 等于新来源，不能静默迁移。
- **HTTPS + SHA-256 不等于安全审核或作者签名。** 被攻陷的仓库能同时改 ZIP 和摘要。
  当前版本没有沙箱、作者签名、密钥轮换或恶意代码扫描，不对第三方代码作安全保证。
- 原生插件运行权限与应用相同；必须在执行前向用户展示信任提醒。

## 6. 发布约定

1. 按现有插件规范生成 ZIP；只发布有权分发的代码，不夹带 Cookie、密钥或个人数据。
2. 固定版本 Release 资产，禁止用 `latest/download` 作为同一版本的可变包地址。
3. 计算 ZIP 长度与 SHA-256，从包内清单填写目录；不要用 Release tag 替代包内版本。
4. 先上传资产，再更新目录并审查 diff；版本变更必须提高 SemVer / versionCode。
5. 发布前运行目录校验，核对目标 ABI 和 manifest。校验脚本不运行插件。
6. 有问题的版本从目录移除；现有客户端不会被远程卸载，不承诺紧急远程停用。

云端目录使用独立 `CPPlayer-Plugins` 仓库。客户端支持多个自定义源，不依赖登录 GitHub。
默认源只作为初始配置，不绕过联网授权；用户移除后不会在下次启动强行添加。

## 7. 实现与验收

- core：`pluginrepo/PluginRepositoryModels.kt`、`PluginRepositoryService.kt`；
  JVM 网络 / 包验证：`JvmPluginRepositoryIO.kt`。
- 安装桥：`MusicBackend`、`ModuleManager`、`FileLyricsPluginStore`、`LyricoLyricsPluginService`。
- app：`PluginRepository` facade、`PluginRepositoryScreen`、`PluginRepositoryStrings`。
- 自动化覆盖：目录 / SemVer / API / 来源 / 取消 / 缓存、真实 ZIP 校验、
  替换失败回滚、重启暂存、歌词运行时失效；UI 各页签中英宽窄离屏渲染。
- 持久化独立 namespace：`plugin_repositories_v1`；
  目录缓存与下载位于宿主 dataDir 的 `plugin-repository/`，不另造桌面数据根目录。
