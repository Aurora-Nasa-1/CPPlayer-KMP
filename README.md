# CPPlayer

> 现代化跨平台音乐播放器（Kotlin Multiplatform）。Android 与 Desktop（Windows / macOS / Linux）共用同一套播放内核、音源插件系统与 Compose Multiplatform 界面。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-purple.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.2-blue.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Sponsor](https://img.shields.io/badge/Sponsor-%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81-ff69b4.svg)](#-赞助与支持-sponsor--support)

---

## 📌 文档入口

Provider 唯一开发入口：[KMP SDK 快速上手](docs/dev/QUICK_PROVIDER_DEV_GUIDE.md)。
生产链路为统一数据层 → 账号会话 → KMP SDK → 适配器；Legacy v1 / Standard v3 已退役，
旧包不再加载。当前范围与尚未接入的能力见 [架构](docs/dev/ARCHITECTURE.md)。

- 📖 **用户手册**：[用户使用指南 (USER_GUIDE.md)](docs/USER_GUIDE.md)（安装、音源导入、账号登录、推送、常见问题）
- 🔌 **插件开发**：[SDK 快速上手](docs/dev/QUICK_PROVIDER_DEV_GUIDE.md) · [业务契约与生命周期](docs/dev/PROVIDER_DEV_GUIDE.md) · [Manifest](docs/dev/MANIFEST_REFERENCE.md)
- 🛠️ **开发者文档**：[开发者文档总索引](docs/README.md)（架构、集成契约、各平台打包）

---

## 🌟 核心特性

- **纯正跨平台一致体验**：基于 Compose Multiplatform 打造，桌面端原生窗口手势吸附、移动端沉浸体验。
- **KMP Provider SDK**：
  - 极简契约：实现 `search`、`get`、`resolve`，批量详情有保序默认实现；无需端点映射、JSON 宿主封套或本地服务。
  - Kotlin、JavaScript 与受信任 JNI/Native 适配器共用同一组类型化业务操作；Provider 的上游 HTTP 通过会话 runtime 发起。
  - 原生声明式设置：音源在 `manifest.json` 中声明配置字段，客户端自动渲染 Material 3 原生设置面板。
  - 账号会话隔离，切换时取消旧请求；媒体流只转发显式请求头。
  - 当前契约含 160 个 typed 业务操作，覆盖登录、目录读写、多类型搜索、评论/私信/通知、一起听、MV/视频、DJ、日历/动态、账号绑定、排序/导入及分块上传；具体音源按能力声明实现，未实现仍明确返回不支持。完整清单见 [SDK 操作表](docs/dev/SDK_OPERATIONS.md)，剩余工作见 [移植状态](docs/dev/SDK_MIGRATION_STATUS.md)。
  - JavaScript SDK 包可导入；Kotlin/JNI Provider 由可信宿主代码注册，不能作为插件 ZIP 安装。独立 [Lyrico 歌词插件](docs/dev/LYRICS_PLUGIN_DEV_GUIDE.md)、AMLL 与本地边车歌词照常使用。
- **发烧级音频解码引擎**：
  - 支持 WASAPI Exclusive 硬件独占直通推流、DSD 解码与无损流落盘缓存。
  - 10 频段参数均衡器 (PEQ)、声道平衡、响度归一化与防爆音削顶。
- **生态集成与本地流输出**：
  - 内置基于 Ktor CIO 的本地 HTTP 流媒体服务（支持字节范围请求与断点续传）。
  - 支持将正在播放的曲目与音频流推送到外部接收端（如游戏 Radio 或车载系统）。
- **多端局域网同步**：
  - 同一局域网内自动发现对等设备，一键转移播放进度并双向同步听歌记录。

---

## 🏗️ 架构与模块划分

```
app-android ──▶ app ──▶ core ──▶ provider-sdk-kmp
```

| 模块 | 职责 | 说明 |
|------|------|------|
| `provider-sdk-kmp` | **Provider 强类型开发契约** | 独立 KMP SDK，业务方法、数据类型及受控宿主能力，不依赖 core/UI/具体 Provider |
| `core` | **后端领域核心** | SDK 会话、统一数据层、播放内核、本地媒体库、下载与流输出；保留部分旧页面的输出投影。**无任何 UI 代码** |
| `app` | **前端跨平台共享** | Compose Multiplatform UI、Voyager 导航、ScreenModel、主题系统，以及桌面端入口 `Main.kt` |
| `app-android` | **Android 平台入口** | 依 AGP 规范独立的应用模块：`MainActivity`、`Application`、Media3 `MediaSessionService` |

前端与后端的统一交互入口为 `cp.player.core.MusicBackend`。详细架构与依赖规则见 [`docs/dev/ARCHITECTURE.md`](docs/dev/ARCHITECTURE.md)。

---

## 🚀 构建与运行

### 环境要求
- JDK 21+（推荐使用 JetBrains Runtime 21）
- Android SDK (API 35+)

### 常用命令

```bash
# 桌面端直接运行
./gradlew :app:run

# 编译后端
./gradlew :core:compileKotlinDesktop
./gradlew :core:compileAndroidMain

# 运行单元测试
./gradlew :core:desktopTest
./gradlew :app:desktopTest

# SDK 与统一数据链路定向回归（隔离构建，检查零跳过 XML）
pwsh -File scripts/verify-provider-chain.ps1

# 打包分发产物
./gradlew :app:packageWindowsVelopack  # Windows 安装包 (Velopack)
./gradlew :app-android:assembleDebug   # Android 安装包
```

发布细节请参阅 [`docs/dev/RELEASE.md`](docs/dev/RELEASE.md)。

---

## ☕ 赞助与支持 (Sponsor & Support)

如果 CPPlayer 为你带来了愉悦的音乐体验，或者对你的学习与日常使用有所帮助，欢迎请作者喝杯咖啡！你的每一份支持都是项目持续迭代与完善的巨大动力 ❤️

| 微信支付 (WeChat Pay) | 支付宝 (Alipay) |
| :---: | :---: |
| <img src="docs/assets/sponsor_wechat.png" width="260" alt="微信支付收款码" /> | <img src="docs/assets/sponsor_alipay.jpg" width="260" alt="支付宝收款码" /> |
| **收款人：Aurora星空 (\*\*森)** | **收款人：Aurora星空 (\*\*森)** |

> 💡 **提示**：扫码赞助时欢迎备注您的 GitHub 账号或昵称。感谢每一位支持 CPPlayer 的伙伴！

---

## 🤝 致谢与开源声明 (Acknowledgments)

本项目中的 **Hi-Res 音频内核**（FlickEngine / WASAPI 独占直通 / DSD 解码）设计思想与底层实现参考并来源于开源项目 **[Flick](https://github.com/moss-apps/Flick)**。

- 核心归属与致谢：感谢 **[moss-apps](https://github.com/moss-apps)** 团队及社区在跨平台音频解码管线与低延迟推流领域的开源贡献（遵循 MIT 许可证）。
- 开源生态鸣谢：[Rodio](https://github.com/RustAudio/rodio)、[Symphonia](https://github.com/pdeljanov/Symphonia)、[cpal](https://github.com/RustAudio/cpal)、[Lyrico](https://github.com/Replica0110/Lyrico) 等优秀开源项目。
- 完整第三方许可证清单：详见 [NOTICE](NOTICE) 与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

## 📄 许可证 (License)

CPPlayer-KMP 项目遵循 [Apache License 2.0](LICENSE) 开源协议。
