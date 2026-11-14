---
layout: home

hero:
  name: CPPlayer
  text: 跨平台音乐播放器
  tagline: Kotlin Multiplatform · Android 与桌面共用同一套播放内核、音源插件系统与界面
  actions:
    - theme: brand
      text: 使用指南
      link: /USER_GUIDE
    - theme: alt
      text: 写一个音源
      link: /dev/providers/QUICKSTART
    - theme: alt
      text: GitHub
      link: https://github.com/Aurora-Nasa-1/CPPlayer-KMP

features:
  - title: 用户指南
    details: 安装、导入音源、播放与音效、外部访问、局域网同步、常见问题
    link: /USER_GUIDE
  - title: Provider 开发
    details: 实现 search / get / resolve 即接入；契约、清单、原生库、分域实现
    link: /dev/providers/
  - title: 架构
    details: 模块边界、唯一远端数据链、会话生命周期、源集划分
    link: /dev/ARCHITECTURE
  - title: 对外集成 API
    details: 让游戏、车载与歌词软件把播放器当音源与播控目标
    link: /dev/INTEGRATION_API
  - title: 构建与发布
    details: 环境、常用任务、验证判据、Velopack 打包与更新链
    link: /dev/BUILD
  - title: 歌词源插件
    details: Lyrico 兼容的 JS 插件：manifest、Platform API、取词回退链
    link: /dev/lyrics/LYRICS_PLUGIN
---

## 读者路径

| 我是谁 | 从这里开始 |
|---|---|
| 用户 | [使用指南](USER_GUIDE.md) |
| 想写音源插件 | [Provider 开发](dev/providers/index.md) → [快速上手](dev/providers/QUICKSTART.md) |
| 想改播放器本体 | [架构](dev/ARCHITECTURE.md) → [构建与验证](dev/BUILD.md) → [贡献指南](../CONTRIBUTING.md) |
| 要把播放器接进游戏 / 车载 | [对外集成 API](dev/INTEGRATION_API.md) |
| 要发版 | [发布流程](dev/RELEASE.md) |

## 全部文档

| 分类 | 文档 | 内容 |
|---|---|---|
| 用户 | [USER_GUIDE](USER_GUIDE.md) | 安装、导入音源、播放、外部访问、局域网同步、FAQ |
| Provider | [QUICKSTART](dev/providers/QUICKSTART.md) | 跑通示例、最小安装包、四条硬约定 |
| Provider | [GUIDE](dev/providers/GUIDE.md) | 方法清单、数据纪律、会话、宿主边界、迁移 |
| Provider | [MANIFEST](dev/providers/MANIFEST.md) | 清单字段、安装事务、原生包、商店分发 |
| Provider | [WORKFLOWS](dev/providers/WORKFLOWS.md) | 按业务域组合方法 |
| Provider | [JS](dev/providers/JS.md) | JS / TS 差异：受控 context、单脚本打包 |
| Provider | [NATIVE](dev/providers/NATIVE.md) | JNI 符号、Wire 字节格式、C ABI v1 |
| 参考 | [SDK_OPERATIONS](dev/providers/SDK_OPERATIONS.md) | 161 个业务操作：tag、方法名、能力名、请求 / 响应 |
| 参考 | [SDK_TYPES](dev/providers/SDK_TYPES.md) | 155 个 DTO：字段、类型、默认值、可空性 |
| 架构 | [ARCHITECTURE](dev/ARCHITECTURE.md) | 模块职责、数据链、生命周期、源集 |
| 架构 | [BUILD](dev/BUILD.md) | 环境、构建、测试、验证脚本、并行会话隔离 |
| 架构 | [INTEGRATION_API](dev/INTEGRATION_API.md) | 对外集成 API v1：发现、鉴权、端点、SSE |
| 架构 | [FLICK_ENGINE](dev/FLICK_ENGINE.md) | 桌面播放链路、能力矩阵、seek / gapless 契约 |
| 架构 | [FONTS](dev/FONTS.md) | 中日拉丁字体栈与字体开关 |
| 架构 | [I18N](dev/I18N.md) | 文案接口树、语言策略、新增文案流程 |
| 歌词 | [LYRICS_PLUGIN](dev/lyrics/LYRICS_PLUGIN.md) | Lyrico 兼容插件：包结构、Platform API、回退链 |
| 发布 | [RELEASE](dev/RELEASE.md) | 版本号、tag 通道、CI、桌面打包、更新链 |
| 归档 | [history](history/index.md) | 已被取代的设计与过程记录 |

## 名词

| 名词 | 含义 |
|---|---|
| `kmp-sdk-v1` | 当前 Provider 契约主版本，写在 manifest 的 `contract` |
| 能力（capability） | 运行期声明，如 `tracks.search` |
| 操作（operation） | schema 的 tag，如 `2`；Wire 与 JNI 用它选方法 |
| typed 业务操作 | 强类型 SDK 方法，相对于早期 `callApi(method, Map)` 字符串协议 |
| 统一层 | `UnifiedMusicSource`，本地优先并绑定当前账号会话 |
| Legacy Provider v1 / Standard v3 | 已退役的旧契约，包不再加载 |
| Lyrico 歌词插件 | 歌词源插件体系，与音源 Provider 独立 |
| 对外集成 API v1 | 第三方控制播放器的 HTTP 契约，与 Provider 契约无关 |

## 在本地预览本站

```bash
npm --prefix docs install
npm --prefix docs run dev      # http://localhost:5173
npm --prefix docs run build    # 产物在 docs/.vitepress/dist
```

文档源就是本目录的 Markdown，站点不维护第二份 HTML。线上版由 GitHub Actions 部署到 Pages。
