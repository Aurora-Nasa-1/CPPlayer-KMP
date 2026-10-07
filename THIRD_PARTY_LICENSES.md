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

## 其余依赖

其余第三方库（Compose Multiplatform、Ktor、kotlinx、Coil、Media3 等）见
`gradle/libs.versions.toml` 与其各自仓库的许可证声明；本文件只登记**源码级移植**。
