# Provider 开发

> 音源插件（Provider）的唯一入口。适用读者：音源作者、SDK 维护者。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-14 · 事实源 `provider-sdk-kmp/`、`provider-contract/schema.mjs`

CPPlayer 自身不含在线曲库。内容全部由 Provider 提供：它实现一组强类型业务方法，宿主负责会话、调度与校验。

## 1. 链路

```text
页面 / 播控 / 下载 / 集成
  └─ Repository / UnifiedMusicSource
      └─ ProviderHost（账号会话、取消、超时、校验）
          └─ SDK MusicProvider
              └─ Kotlin / JavaScript / JNI·C ABI 适配器
                  └─ 音源自己的上游服务
```

| 层 | 负责 | 不负责 |
|---|---|---|
| Provider | 实现业务方法、解析自家上游、返回 DTO | 端口分配、本地服务、宿主 JSON 封套、账号存储 |
| 宿主 | 会话隔离、取消、超时、能力校验、安装事务 | 代替 Provider 校验上游状态 |
| 统一层 | 本地数据优先、按账号会话路由 | 跨音源兜底、按标题猜音源 |

## 2. 阅读路径

| 目标 | 顺序 |
|---|---|
| 第一次写 Provider | [QUICKSTART](QUICKSTART.md) → [GUIDE](GUIDE.md) → [MANIFEST](MANIFEST.md) |
| 写 JS / TS | QUICKSTART → [JS](JS.md) → [WORKFLOWS](WORKFLOWS.md) |
| 写原生库 | QUICKSTART → [NATIVE](NATIVE.md) → [MANIFEST](MANIFEST.md) |
| 查签名与字段 | [SDK_OPERATIONS](SDK_OPERATIONS.md)、[SDK_TYPES](SDK_TYPES.md) |
| 按业务域实现 | [WORKFLOWS](WORKFLOWS.md) |
| 发布到商店 | [MANIFEST 第 6 节](MANIFEST.md#6-商店仓库分发) |
| 改 SDK 本身 | [provider-contract/README](../../../provider-contract/README.md) |

## 3. 文档

| 文档 | 内容 | 读者 |
|---|---|---|
| [QUICKSTART](QUICKSTART.md) | 本地示例跑通、最小安装包、四条硬约定 | 新手 |
| [GUIDE](GUIDE.md) | 方法清单、数据纪律、会话生命周期、宿主边界、迁移 | 全部作者 |
| [MANIFEST](MANIFEST.md) | `manifest.json` 字段、安装更新事务、原生包布局、商店分发 | 全部作者、维护者 |
| [JS](JS.md) | `globalThis.provider`、`context` 能力、TS 类型与打包 | JS / TS 作者 |
| [NATIVE](NATIVE.md) | JNI 符号、Wire 字节格式、C ABI、双导出 | 原生作者 |
| [WORKFLOWS](WORKFLOWS.md) | 搜索、播放、评论、一起听、MV/DJ、上传、登录 | 全部作者 |
| [SDK_OPERATIONS](SDK_OPERATIONS.md) | 161 个业务操作的 tag、能力名、请求/响应 | 参考 |
| [SDK_TYPES](SDK_TYPES.md) | 155 个 DTO 的字段、类型、默认值 | 参考 |

两张参考由 `provider-contract/generate.mjs` 生成，源头是 `schema.mjs`（结构）与 `documentation.mjs`（语义）。改文档不手改产物，改源头再生成。

## 4. 独立体系

| 体系 | 位置 | 说明 |
|---|---|---|
| 歌词源插件（Lyrico 兼容） | [../lyrics/LYRICS_PLUGIN.md](../lyrics/LYRICS_PLUGIN.md) | 自己的 manifest 与 JS API，与 Provider 无关 |
| 对外集成 API v1 | [../INTEGRATION_API.md](../INTEGRATION_API.md) | 第三方控制播放器的 HTTP 契约，与 Provider 无关 |
| 本地媒体、边车歌词、AMLL | 用户侧 | 不受 Provider 契约影响 |

## 5. 术语

| 术语 | 含义 |
|---|---|
| `kmp-sdk-v1` | 当前 Provider 契约主版本，写在 manifest 的 `contract` |
| 能力（capability） | 运行时声明，如 `tracks.search`；决定请求是否被受理 |
| 操作（operation） | schema 里的 tag，如 `2`；Wire 与 JNI 用它选方法 |
| 会话（session） | 由 Provider / 账号 / 凭证版本 / 设置快照构成，不可变 |
| 统一层 | `UnifiedMusicSource`，本地优先并绑定当前会话 |
| Legacy v1 / Standard v3 | 已退役的旧契约，包不再加载 |

## 相关

- 架构与源集：[../ARCHITECTURE.md](../ARCHITECTURE.md)
- 合同生成与校验：[../../../provider-contract/README.md](../../../provider-contract/README.md)
- SDK 源码说明：[../../../provider-sdk-kmp/README.md](../../../provider-sdk-kmp/README.md)
