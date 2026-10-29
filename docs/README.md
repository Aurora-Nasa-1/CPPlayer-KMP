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
| [QUICK_PROVIDER_DEV_GUIDE.md](dev/QUICK_PROVIDER_DEV_GUIDE.md) | **现代插件开发极速指南 (Standard v3)** | 开发轻量自定义音源、第三方平台移植（5 分钟上手） |
| [PROVIDER_DEV_GUIDE.md](dev/PROVIDER_DEV_GUIDE.md) | **音源插件开发完整参考手册** | 全部宿主标准端点参考（apiMap 键名清单）、健康监控、打包与调试 |
| [MANIFEST_REFERENCE.md](dev/MANIFEST_REFERENCE.md) | **manifest.json 单一事实源规范** | 音源 manifest 全字段、capabilities 语义、响应码表、音质档位、设置表单与更新机制（各指南冲突时以本文为准） |
| [LYRICS_PLUGIN_DEV_GUIDE.md](dev/LYRICS_PLUGIN_DEV_GUIDE.md) | **歌词源插件（Lyrico / Rhino）开发指南** | 为无歌词曲目接入 TTML/逐字歌词源；独立 manifest schema、宿主 JS API、生命周期与调试 |

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
| [WINDOWS_PACKAGING.md](dev/WINDOWS_PACKAGING.md) | Windows 端 Velopack 安装包与自动更新机制 |
| [LINUX_PACKAGING.md](dev/LINUX_PACKAGING.md) | Linux tar.gz 与 AUR (`cpplayer-bin`) 发布 |
| [JBR_PACKAGING.md](dev/JBR_PACKAGING.md) | 桌面端 JetBrains Runtime (JBR) 打包与窗口装饰 |

---

## 📌 快速导航
- 仓库主页与构建说明：[`README.md`](../README.md)
