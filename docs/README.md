# CPPlayer 文档导航

> 全部文档按受众分三档：**用户文档**（怎么用）、**开发者文档**（怎么改 / 怎么接入）、
> **历史归档**（一次性方案与审计，记录决策来历，内容以当时的仓库状态为基准）。

## 用户文档

| 文档 | 内容 |
|------|------|
| [USER_GUIDE.md](USER_GUIDE.md) | 安装、首次启动、音源导入、账号登录、播放、本地服务器与推送、常见问题 |

## 开发者文档（[`dev/`](dev/)）

| 文档 | 内容 | 什么时候读 |
|------|------|------------|
| [dev/ARCHITECTURE.md](dev/ARCHITECTURE.md) | 模块职责、依赖规则、源集分层、边界越界点清单 | **新增代码前必读** |
| [dev/PROVIDER_DEV_GUIDE.md](dev/PROVIDER_DEV_GUIDE.md) | 音源（Provider）插件开发指南，面向第三方音源作者 | 给 CPPlayer 写音源模块 |
| [dev/INTEGRATION_API.md](dev/INTEGRATION_API.md) | 对外集成契约（v1）：让第三方软件把 CPPlayer 当音源用 | 对接 CPPlayer 的 `/api/v1/...` 端点 |
| [dev/RELEASE.md](dev/RELEASE.md) | 发布流程 | 发版 |
| [dev/JBR_PACKAGING.md](dev/JBR_PACKAGING.md) | 桌面端 JBR（JetBrains Runtime）打包方案 | 动桌面端打包 / 窗口 chrome |

## 历史归档（[`history/`](history/)）

> 这些文档记录**当时**的诊断与方案，多数已实施完毕。文中引用的文件路径、
> 行号以写作时为准，不代表当前代码；正式的架构约定以 [`dev/ARCHITECTURE.md`](dev/ARCHITECTURE.md) 为准。

| 文档 | 状态 |
|------|------|
| [history/RESTRUCTURE_PLAN.md](history/RESTRUCTURE_PLAN.md) | 目录结构迁移方案（2026-09-25），已实施 |
| [history/DEAD_CODE_AUDIT.md](history/DEAD_CODE_AUDIT.md) | 死代码审计（2026-09-26，含 2026-09-30 订正） |
| [history/HARMONYOS_FEASIBILITY.md](history/HARMONYOS_FEASIBILITY.md) | 鸿蒙（HarmonyOS NEXT）适配可行性分析（2026-09-26） |
| [history/HOME_CARD_REFRESH_PLAN.md](history/HOME_CARD_REFRESH_PLAN.md) | 首页卡片重复度改造（已全部实施） |
| [history/INTEGRATION_PLAN.md](history/INTEGRATION_PLAN.md) | 对外集成方案分阶段计划（契约手册见 `dev/INTEGRATION_API.md`；§13 能力清单同步纪律仍在生效） |
| [history/PLAYER_MORE_MENU_PORT.md](history/PLAYER_MORE_MENU_PORT.md) | 播放页「更多」菜单移植方案（已完成约 90%+） |
| [history/SETTINGS_REDESIGN.md](history/SETTINGS_REDESIGN.md) | 设置界面重构方案（2026-09-30） |
| [history/VISUAL_EXPRESSIVE_PLAN.md](history/VISUAL_EXPRESSIVE_PLAN.md) | 视觉优化方案（对标 M3 Expressive） |

## 其他入口

- 仓库门面与构建说明：[`README.md`](../README.md)
- AI 协作硬约定（本仓库动手前必读）：[`AGENTS.md`](../AGENTS.md)
