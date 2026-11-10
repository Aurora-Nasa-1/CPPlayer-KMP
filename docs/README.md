# 文档

CPPlayer 是个跨平台音乐播放器（Kotlin Multiplatform）。这里列出全部文档。

线上版：<https://aurora-nasa-1.github.io/CPPlayer-KMP/>。本地预览跑 `python scripts/build-docs-site.py`，产物在 `site/`。

## 用户

- [使用指南](USER_GUIDE.md)：安装、导入音源、登录、播放、局域网推送、常见问题。

播放器本身不带受版权保护的在线音源，内容全靠插件提供，这点得先知道。

## Provider 开发

SDK 在 `provider-sdk-kmp/`，Kotlin / JavaScript / JNI 三类宿主共用一套契约。旧的 Legacy Provider v1、Standard v3 和 `provider-sdk/` 都已退役，不再加载。

建议按顺序看：

1. [QUICK_PROVIDER_DEV_GUIDE.md](dev/QUICK_PROVIDER_DEV_GUIDE.md) — 从零跑通一个音源。带本地示例服务，不需要外部账号。
2. [PROVIDER_DEV_GUIDE.md](dev/PROVIDER_DEV_GUIDE.md) — 业务契约：要实现哪些函数、返回什么。
3. [MANIFEST_REFERENCE.md](dev/MANIFEST_REFERENCE.md) — manifest.json、打包、安装、更新与回滚。
4. [PROVIDER_WORKFLOWS.md](dev/PROVIDER_WORKFLOWS.md) — 各领域做法：评论/私信、一起听、MV/DJ、导入/上传、认证。
5. [JS_PROVIDER_DEV_GUIDE.md](dev/JS_PROVIDER_DEV_GUIDE.md) — JS/TS 的差异：受控 context、单文件打包。

写 Kotlin/JNI 适配器的再看 [SDK_MIGRATION_STATUS.md](dev/SDK_MIGRATION_STATUS.md)（已接契约、接入点、待移植范围）。JNI/Native 包的格式见 [Manifest 原生 SDK 包](dev/MANIFEST_REFERENCE.md#原生-sdk-包)。

歌词插件是另一套东西，跟音源 Provider 没关系：[LYRICS_PLUGIN_DEV_GUIDE.md](dev/LYRICS_PLUGIN_DEV_GUIDE.md)（Lyrico 兼容，QuickJS 宿主，自己的 manifest 和 JS API）。

## SDK 参考

当字典查，不用通读：

- [SDK_OPERATIONS.md](dev/SDK_OPERATIONS.md)：161 个业务操作，方法名、能力名、请求/响应类型。
- [SDK_TYPES.md](dev/SDK_TYPES.md)：155 个 DTO 的字段、单位、默认值、可空性。
- [SDK_MIGRATION_STATUS.md](dev/SDK_MIGRATION_STATUS.md)：迁移进度。

这两份是 `provider-contract/generate.mjs` 生成的，源头是 [`schema.mjs`](../provider-contract/schema.mjs)（结构）和 [`documentation.mjs`](../provider-contract/documentation.mjs)（描述）。别手改文档，改完源头重新生成。

## 架构与集成

- [ARCHITECTURE.md](dev/ARCHITECTURE.md)：分层和模块边界（`app-android` / `app` / `core` / `provider-sdk-kmp`），源集依赖规则。
- [INTEGRATION_API.md](dev/INTEGRATION_API.md)：第三方应用和游戏控制播放器的 HTTP 接口。
- [FLICK_ENGINE.md](dev/FLICK_ENGINE.md)：FlickEngine 播放链路、能力矩阵、JNI 导出。
- [FONTS.md](dev/FONTS.md)：中日拉丁字体栈。
- [I18N.md](dev/I18N.md)：多语言机制和文案规范。

## 打包与发布

- [RELEASE.md](dev/RELEASE.md)：发布流程、版本号、产物校验。Windows 包是 Velopack 打的，不是 jpackage MSI。

## 归档

[history/](history/README.md) 放已被取代的设计提案和过程记录，用来查当年为什么那么定，不代表现在怎么做。

- [PROVIDER_SDK_PROPOSAL.md](history/PROVIDER_SDK_PROPOSAL.md)：Provider SDK 早期设计，已被上面的开发指南取代。

## 名词

| 名词 | 含义 |
|------|------|
| `kmp-sdk-v1` | 当前 Provider 契约版本，写在 manifest 的 `contract` 字段 |
| typed 业务操作 | SDK 的强类型方法，相对早期 `callApi(method, Map)` 字符串协议说的 |
| Legacy Provider v1 / Standard v3 | 已退役的旧契约，包不再加载 |
| Lyrico 歌词插件 | 歌词源插件体系，和音源 Provider 独立 |
| 对外集成 API v1 | 第三方控制播放器的 HTTP 契约，和 Provider 契约无关 |

---

构建命令和模块说明见仓库根的 [README.md](../README.md)，AI 协作规则见 [AGENTS.md](../AGENTS.md)。
