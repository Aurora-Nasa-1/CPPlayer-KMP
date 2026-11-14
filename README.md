# CPPlayer

> 跨平台音乐播放器（Kotlin Multiplatform）。Android 与 Desktop（Windows / macOS / Linux）共用一套播放内核、音源插件系统与 Compose Multiplatform 界面。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.2-blue.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Sponsor](https://img.shields.io/badge/Sponsor-%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81-ff69b4.svg)](#赞助与支持)

## 为什么存在

在线曲库的接口一直在变，播放器内核却应该稳定。CPPlayer 把两者切开：播放、缓存、歌词、局域网同步属于内核；内容接入属于插件。

| 结果 | 做法 |
|---|---|
| 换音源不改播放器 | 音源实现强类型业务契约，宿主负责会话与调度 |
| 换音源不丢数据 | 会话按音源 / 账号 / 凭证版本 / 设置隔离 |
| 三端一致 | Compose Multiplatform 共享 UI，播放内核共用 |

播放器本身不含受版权保护的在线音源，内容全部由插件提供。

## 特性

| 能力 | 说明 |
|---|---|
| 跨平台一致体验 | Compose Multiplatform；桌面原生窗口手势吸附，移动端沉浸式布局 |
| KMP Provider SDK | 实现 `search`、`get`、`resolve` 即可接入；批量详情有保序默认实现 |
| 三类宿主共用契约 | Kotlin、JavaScript、JNI / C ABI 适配器使用同一组类型化业务操作 |
| 声明式设置 | 音源在 `manifest.json` 声明字段，客户端渲染 Material 3 设置面板 |
| 账号隔离 | 切换账号取消旧请求；媒体流只转发显式声明的请求头 |
| 契约规模 | 161 个 typed 业务操作，覆盖登录、目录读写、多类型搜索、评论 / 私信 / 通知、一起听、MV / 视频、DJ、日历 / 动态、账号绑定、排序 / 导入、分块上传 |
| 歌词 | 内置 AMLL 与本地边车（`.lrc` / `.ttml`）；独立 Lyrico 歌词插件 |
| 音频解码 | WASAPI Exclusive 硬件独占、DSD 解码、无损流落盘缓存 |
| 音效 | 10 频段参数均衡器（PEQ）、声道平衡、响度归一化、防爆音削顶 |
| 本地流输出 | Ktor CIO HTTP 流媒体服务，支持字节范围与断点续传；可推送到车载或游戏电台 |
| 局域网同步 | 自动发现对等设备，转移播放进度并双向同步听歌记录 |

契约存在不等于某个音源已实现；具体音源按声明能力接入，未实现仍返回 `Unsupported`。

## 架构

```text
app-android ──▶ app ──▶ core ──▶ provider-sdk-kmp
```

| 模块 | 职责 | 边界 |
|---|---|---|
| `provider-sdk-kmp` | Provider 强类型契约、数据类型、受控 runtime | 不依赖 core、Compose、宿主数据库或具体音源 |
| `core` | 会话、统一数据层、播放内核、本地库、下载、流输出 | 无 UI 代码 |
| `app` | Compose UI、Voyager 导航、ScreenModel、主题、桌面入口 | 经 `MusicBackend` 访问后端 |
| `app-android` | `MainActivity`、`Application`、Media3 `MediaSessionService` | 平台入口 |

前后端唯一交互入口是 `cp.player.core.MusicBackend`。依赖规则与源集划分见 [ARCHITECTURE](docs/dev/ARCHITECTURE.md)。

## 快速开始

前置：JDK 21+（推荐 JetBrains Runtime 21）、Android SDK（API 35+）。

```bash
./gradlew :app:run                    # 桌面端运行
./gradlew :core:compileKotlinDesktop  # 编译后端
./gradlew :app-android:assembleDebug  # Android 包
```

| 目标 | 命令 / 文档 |
|---|---|
| 完整构建、测试与验证 | [BUILD](docs/dev/BUILD.md) |
| 打包与发布 | [RELEASE](docs/dev/RELEASE.md) |
| 写一个音源 | [QUICKSTART](docs/dev/providers/QUICKSTART.md) |
| 使用与设置 | [USER_GUIDE](docs/USER_GUIDE.md) |

`scripts/verify-provider-chain.ps1` 仅 Windows 可用（内部调用 `gradlew.bat`），判据是 JUnit XML 的失败 / 错误 / 跳过全为 0。

## 文档

在线阅读：<https://aurora-nasa-1.github.io/CPPlayer-KMP/>

| 入口 | 内容 |
|---|---|
| [docs/index.md](docs/index.md) | 全部文档索引与名词表 |
| [用户指南](docs/USER_GUIDE.md) | 安装、导入音源、登录、播放、外部访问、FAQ |
| [Provider 开发](docs/dev/providers/index.md) | 契约、清单、原生库、分域实现、生成参考 |
| [架构](docs/dev/ARCHITECTURE.md) | 模块边界、数据链、源集 |
| [对外集成 API](docs/dev/INTEGRATION_API.md) | 第三方控制播放器的 HTTP 契约 |
| [贡献指南](CONTRIBUTING.md) | 构建、测试、提交与文档改动流程 |

## 赞助与支持

如果 CPPlayer 对你有帮助，欢迎请作者喝杯咖啡。

| 微信支付 | 支付宝 |
| :---: | :---: |
| <img src="docs/assets/sponsor_wechat.png" width="260" alt="微信支付收款码" /> | <img src="docs/assets/sponsor_alipay.jpg" width="260" alt="支付宝收款码" /> |
| 收款人：Aurora星空 (\*\*森) | 收款人：Aurora星空 (\*\*森) |

扫码时欢迎备注 GitHub 账号或昵称。

## 致谢

Hi-Res 音频内核（FlickEngine / WASAPI 独占直通 / DSD 解码）的设计与底层实现参考开源项目 [Flick](https://github.com/moss-apps/Flick)。

| 对象 | 关系 |
|---|---|
| [moss-apps](https://github.com/moss-apps) | 跨平台音频解码管线与低延迟推流（MIT） |
| [Rodio](https://github.com/RustAudio/rodio) | 音频输出 |
| [Symphonia](https://github.com/pdeljanov/Symphonia) | 解码 |
| [cpal](https://github.com/RustAudio/cpal) | 设备与独占模式 |
| [Lyrico](https://github.com/Replica0110/Lyrico) | 歌词插件 API |

完整清单见 [NOTICE](NOTICE) 与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 许可证

[Apache License 2.0](LICENSE)。
