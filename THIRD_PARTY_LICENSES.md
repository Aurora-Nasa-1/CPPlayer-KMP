# Third-Party Licenses / 第三方许可证

本文件记录 CPPlayer 使用或**移植**的第三方代码、其版权归属与许可证。
凡从外部项目移植进本仓库的源码，均在**文件头部**标注来源、上游许可证与改动说明。

---

## Halcyon（Apache-2.0）

- **上游项目**：Halcyon — https://github.com/Kifranei/Halcyon
- **许可证**：Apache License 2.0（全文见 `licenses/Apache-2.0.txt`）
- **上游版权**：Copyright the Halcyon contributors（上游 LICENSE 未单列版权行）
- **移植范围**：歌词源插件体系（Lyrico Plugin API 宿主实现）
  - 上游对应文件：`app/src/main/java/com/ella/music/plugin/**`
  - 本仓库落点：`core/src/{commonMain,jvmMain}/kotlin/cp/player/core/lyricsplugin/**`
- **改动说明**：按 Apache-2.0 §4(b) 的要求，移植版本已作如下修改：
  1. 包名由 `com.ella.music.plugin.*` 改为 `cp.player.core.lyricsplugin.*`；
  2. JS 运行时由 Android 专有的 `wang.harlon.quickjs:wrapper-android` 改为跨平台 JVM 的
     Mozilla Rhino，以便在 KMP 的桌面端与 Android 端共用同一套宿主契约；
  3. 存储层由 Android `Context`/`assets` 改为 KMP 的 `PlatformContext` + 文件系统；
  4. 结果模型对齐本仓库的 `cp.player.core.model.LyricLine`；
  5. 移除了上游的 i18n 资源包（`PluginStrings`）与 XML/AES 等本项目暂未使用的宿主 API，
     保留的宿主 API 清单见 `HostApiRegistry.kt`。

> **未移植**：本仓库**未**复制 Halcyon 的音频引擎、UI、解码器等其余代码。

---

## Lyrico Plugin API（上游规范）

- **规范来源**：Lyrico — https://github.com/Replica0110/Lyrico
- **许可证**：Apache-2.0
- **说明**：歌词源插件遵循 Lyrico Plugin API（本仓库声明支持 `pluginApiVersion` 1–5、
  `hostApiVersion` 1–4）。插件契约（`manifest.json` 字段、`Platform.*` 宿主面、
  `globalThis.searchSongs / getLyrics / searchCovers` 入口）来自该公开规范，
  非 Halcyon 私有。任何按该规范编写的插件都可在本宿主运行。

---

## Mozilla Rhino（MPL-2.0）

- **用途**：JVM 侧 JavaScript 引擎，运行 Lyrico 格式的歌词源插件。
- **许可证**：Mozilla Public License 2.0 — https://github.com/mozilla/rhino

---

## 其余依赖

其余第三方库（Compose Multiplatform、Ktor、kotlinx、Coil、Media3 等）见
`gradle/libs.versions.toml` 与其各自仓库的许可证声明；本文件只登记**源码级移植**。
