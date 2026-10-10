# Third-Party Licenses / 第三方许可证

本文件记录 CPPlayer 使用或**移植**的第三方代码、其版权归属与许可证。
凡从外部项目移植进本仓库的源码，均在**文件头部**标注来源、上游许可证与改动说明。

---

## 歌词源插件宿主（基于 Lyrico Plugin API 规范，Apache-2.0）

- **规范来源**：Lyrico — https://github.com/Replica0110/Lyrico
- **许可证**：Apache-2.0
- **本仓库落点**：`core/src/{commonMain,jvmMain}/kotlin/cp/player/core/lyricsplugin/**`
- **说明**：歌词源插件体系遵循公开的 Lyrico Plugin API（本仓库声明支持
  `pluginApiVersion` 1–5、`hostApiVersion` 1–4）。插件契约（`manifest.json` 字段、
  `Platform.*` 宿主面、`globalThis.searchSongs / getLyrics / searchCovers` 入口）
  均来自该公开规范。任何按该规范编写的插件都可在本宿主运行。
- **与规范的关系**：本宿主的 JS 运行时选用跨平台 JVM 的 Mozilla Rhino（而非某个
  Android 专有的 QuickJS wrapper），以便在 KMP 的桌面端与 Android 端共用同一套宿主契约；
  存储层走 KMP 的 `PlatformContext` + 文件系统；结果模型对齐本仓库的
  `cp.player.core.model.LyricLine`；未实现规范中本项目暂未使用的部分宿主 API，
  保留的宿主 API 清单见 `HostApiRegistry.kt`。

---

## Mozilla Rhino（MPL-2.0）

- **用途**：JVM 侧 JavaScript 引擎，运行 Lyrico 格式的歌词源插件。
- **许可证**：Mozilla Public License 2.0 — https://github.com/mozilla/rhino

---

## Halcyon（Apache-2.0）——歌词对外投放体系

- **上游**：Halcyon — https://github.com/Kifranei/Halcyon
- **许可证**：Apache-2.0（上游 LICENSE 无单列版权行、无 NOTICE 文件）
- **本仓库落点**：
  - `core/src/commonMain/kotlin/cp/player/core/lyricpush/**`（配置模型与纯协议层）
  - `core/src/androidMain/kotlin/cp/player/core/lyricpush/**`（各渠道 bridge）
  - `app-android/src/main/kotlin/cp/player/app/LyricMetadataSink.kt`
- **移植了什么**：词幕（Lyricon）、SuperLyric、Lyric Getter、HyperOS 超级岛、
  ColorOS 锁屏岛、Flyme 状态栏歌词 / 浮动通知歌词、Android 16 实时活动歌词、
  媒体通知歌词（蓝牙 / 车机）共八条渠道的**协议实现与设置项**。
- **改动清单**（逐文件写在头部注释里，这里给总览）：
  1. Halcyon 的 `org.json` / `String.format(Locale.US)` 换成自带的
     `LyricPushJson` 与手写补零（KMP `commonMain` 没有这两者）。
  2. 分散在 `PlayerViewModel` 的「变化了吗 → 该发什么」判定收敛为单一协调器
     `AndroidLyricPusher`：跨平台核心层只能看到一个投放出口。
  3. **未移植**的部分（均为有意的设计取舍，不是遗漏）：
     - Shizuku + 隐藏 `IConnectivityManager` 的 XMSF 断网旁路（只保留「直接发送」路径）；
     - 超级岛的媒体控制按钮与分享卡片（需要上游的 `R.drawable` 矢量资源）；
     - `PlaybackTickerState`（上游用于就地改写自家播放通知，本仓库的通知归 media3
       `DefaultMediaNotificationProvider` 管，改写的入口是 `LyricMetadataSink`）。
  4. 专辑封面改为**可选注入**（`LyricPushArtworkProvider`）：`core` 不引入图片加载依赖，
     未注入时超级岛/通知走无封面 + 默认强调色的退化路径。
  5. 小图标由 `Canvas` 合成（`core` 没有 `res/drawable`）。
- **反向依赖说明**：词幕 Provider API（`io.github.proify.lyricon:provider`）、
  SuperLyric（`com.github.HChenX:SuperLyricApi`）、Lyric Getter
  （`com.github.HChenX:Lyric-Getter-Api`）、HyperOS 焦点通知
  （`com.xzakota.hyper.notification:focus-api`）均为各自上游的公开 AAR，
  见 `gradle/libs.versions.toml`。协议细节（intent action、extras 键名、
  `miui.focus.*` 布局字段）以上游契约为准，本仓库不做改写。

---

## 其余依赖

其余第三方库（Compose Multiplatform、Ktor、kotlinx、Coil、Media3 等）见
`gradle/libs.versions.toml` 与其各自仓库的许可证声明；本文件只登记**源码级移植**。
