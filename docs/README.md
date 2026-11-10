# CPPlayer 文档索引

欢迎查阅 CPPlayer 文档。本文档分为**用户使用指南**与**开发者手册**两大板块。

---

## 📖 用户文档

| 文档 | 说明 |
|------|------|
| [USER_GUIDE.md](USER_GUIDE.md) | 安装分发、首次启动、音源模块导入、账号登录、音频播放、局域网流推送与常见问题 |

---

## 🛠️ 开发者文档 (`docs/dev/`)

### 插件与音源开发（核心）

| 文档 | 说明 | 适用场景 |
|------|------|----------|
| [QUICK_PROVIDER_DEV_GUIDE.md](dev/QUICK_PROVIDER_DEV_GUIDE.md) | **KMP SDK 快速上手** | 运行本地示例，开发 search/get/resolve 并打包 |
| [PROVIDER_DEV_GUIDE.md](dev/PROVIDER_DEV_GUIDE.md) | **当前 Provider 业务契约** | 数据语义、账号会话、支持范围、错误与验证 |
| [SDK_OPERATIONS.md](dev/SDK_OPERATIONS.md) | **完整操作参考（生成）** | 逐项方法、能力名、请求/响应链接与行为 |
| [SDK_TYPES.md](dev/SDK_TYPES.md) | **字段与类型参考（生成）** | 全部 DTO 字段、单位、默认值、空值与边界 |
| [PROVIDER_WORKFLOWS.md](dev/PROVIDER_WORKFLOWS.md) | **分域实现流程** | 评论/私信、一起听、MV/DJ、导入/上传、认证与账号 |
| [SDK_MIGRATION_STATUS.md](dev/SDK_MIGRATION_STATUS.md) | **SDK 迁移状态** | 已有契约、应用接入与待移植范围 |
| [MANIFEST_REFERENCE.md](dev/MANIFEST_REFERENCE.md) | **SDK 包清单与安装规范** | kmp-sdk-v1、origin 白名单、精确 ID 更新与回滚 |
| [JS_PROVIDER_DEV_GUIDE.md](dev/JS_PROVIDER_DEV_GUIDE.md) | **SDK JavaScript / TypeScript** | globalThis.provider、受控 context、类型与单文件打包 |
| [KMP SDK 源码](../provider-sdk-kmp) | **独立业务接口与跨语言数据类型** | Kotlin 实现、JS 类型、生成契约 |
| [LYRICS_PLUGIN_DEV_GUIDE.md](dev/LYRICS_PLUGIN_DEV_GUIDE.md) | **歌词源插件开发指南（Lyrico 兼容 / QuickJS 宿主）** | 为无歌词曲目接入 TTML/逐字歌词源；独立 manifest schema、宿主 JS API、生命周期与调试 |

Legacy Provider v1、Standard v3 与旧 `provider-sdk/` 不再是可安装契约。
`PROVIDER_SDK_PROPOSAL.md` / `PROVIDER_TYPED_RUNTIME.md` 是过程记录，其兼容回退描述已被现行指南取代。
对外集成 HTTP `/api/v1/...` 与歌词插件的版本号独立，不属于此次退役范围。

### 核心架构与生态集成

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](dev/ARCHITECTURE.md) | 架构分层、模块边界 (`app-android` / `app` / `core`) 与源集依赖规则 |
| [INTEGRATION_API.md](dev/INTEGRATION_API.md) | 对外集成契约 (v1)：第三方应用与游戏通过 HTTP 访问播放器媒体流与数据 |
| [I18N.md](dev/I18N.md) | 国际化 (i18n) 机制与多语言文案规范 |

### 打包分发与发布

| 文档 | 说明 |
|------|------|
| [RELEASE.md](dev/RELEASE.md) | 发布流程、版本号规范与产物校验 |

---

## 📌 快速导航
- 仓库主页与构建说明：[`README.md`](../README.md)
