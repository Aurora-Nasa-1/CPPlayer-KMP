---
kind: build_system
name: Gradle KMP 多模块构建与桌面原生打包
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - gradle.properties
    - gradle/libs.versions.toml
    - kmp-pro/build.gradle.kts
    - app/build.gradle.kts
    - androidApp/build.gradle.kts
---

## 1. 使用的系统与工具

- **构建系统**：Gradle（通过 `gradlew` 包装器），采用 Kotlin DSL（`.kts`）。
- **Kotlin Multiplatform (KMP)**：核心跨平台能力，目标包括 Android (`androidMain`)、JVM Desktop (`desktopMain`) 以及共享的 `commonMain`；`kmp-pro` 模块还额外定义了一个自定义 source set `jvmMain`，被 `androidMain` 和 `desktopMain` 共同依赖，形成 `commonMain ← jvmMain ← {androidMain, desktopMain}` 的三层继承结构。
- **Android 插件**：AGP 9.1.1（`com.android.application` / `com.android.library` / `com.android.kotlin.multiplatform.library`），compileSdk 36、minSdk 29、targetSdk 35。
- **Compose**：Jetpack Compose + Compose for Desktop，用于 UI 层构建与桌面应用分发。
- **桌面原生打包**：通过 `compose.desktop.nativeDistributions` 直接输出 `.dmg`、`.deb`、`.msi` 三种格式。
- **版本管理**：使用 Gradle Version Catalog（`gradle/libs.versions.toml`）集中声明所有插件与库版本。
- **代理/缓存**：`gradle.properties` 中配置了本地 HTTP/HTTPS 代理（`127.0.0.1:7897`），并设置 JVM 堆 `-Xmx2048m`。

## 2. 关键文件

- `settings.gradle.kts`：声明仓库名 `KMP-PRO`，包含三个子模块 `:kmp-pro`、`:app`、`:androidApp`，并统一配置 `pluginManagement` 与 `dependencyResolutionManagement` 的仓库源（google、mavenCentral、gradlePluginPortal）。
- `build.gradle.kts`（根）：仅以 `apply false` 方式预声明各插件，由子模块按需启用。
- `gradle/libs.versions.toml`：集中管理 AGP、Kotlin 2.4.10、Coroutines 1.9.0、Ktor 3.0.3、Compose 1.11.1、Voyager、Media3、Coil 等全部依赖版本。
- `gradle.properties`：全局构建参数（AndroidX、非传递 RClass、Kotlin 代码风格、默认层次模板关闭、代理）。
- `kmp-pro/build.gradle.kts`：后端核心 KMP 库，定义 `jvmMain` 中间 source set，并在 `desktopMain` 中按 OS 动态选择 JavaFX classifier（mac-aarch64/mac/windows/linux）。
- `app/build.gradle.kts`：UI 层 KMP 库，依赖 `:kmp-pro`，配置 Compose Desktop 入口类 `cp.player.app.MainKt` 及原生包名 `CPPlayer`、版本号 `1.0.0`。
- `androidApp/build.gradle.kts`：Android 应用壳，从 Git 读取 `git rev-parse --short HEAD` 注入 `BuildConfig.GIT_SHA`，依赖 `:app`。

## 3. 架构与约定

- **多模块分层**：`kmp-pro`（业务后端/Provider 抽象/播放控制）→ `app`（KMP Compose UI）→ `androidApp`（Android 启动壳）。依赖方向单向向下，无循环。
- **Source Set 约定**：`commonMain` 放跨平台逻辑；`jvmMain` 放 JVM 通用实现（如 HTTP 客户端）；`androidMain` 与 `desktopMain` 分别覆盖平台差异（媒体播放器、设置存储、主题等）。
- **依赖集中化**：所有第三方库版本在 `libs.versions.toml` 的 `[versions]` 段声明，模块内通过 `libs.xxx` 引用，避免硬编码版本号。
- **插件集中声明**：根 `build.gradle.kts` 仅做插件 `apply false` 注册，实际启用放在对应模块，便于独立开关。
- **桌面分发**：通过 `compose.desktop.nativeDistributions.targetFormats` 一次性产出 macOS DMG、Linux Deb、Windows MSI 安装包，包名固定为 `CPPlayer`。
- **Android 构建信息**：通过 `ProcessBuilder("git", "rev-parse", "--short", "HEAD")` 在编译期获取提交 SHA 写入 `BuildConfig`，用于版本追踪。

## 4. 约定与约束

- **KMP 目标限制**：当前工程未配置 JS/Wasm 目标，仅支持 Android 与 JVM Desktop。
- **最小 SDK 约束**：Android compileSdk 36、minSdk 29；Desktop JVM target 设为 `JvmTarget.JVM_21`，要求运行环境至少 JDK 21。
- **JavaFX 平台适配**：`kmp-pro` 中根据 `System.getProperty("os.name")` 与 `os.arch` 动态拼接 JavaFX classifier（`mac-aarch64`、`mac`、`win`、`linux`），确保桌面端正确加载原生图形库。
- **仓库源顺序**：优先 google → mavenCentral → gradlePluginPortal，保证 Android 相关依赖可正常解析。
- **构建代理**：强制使用本地 `127.0.0.1:7897` 作为 HTTP/HTTPS 代理，离线或代理不可用时构建会失败。
- **版本同步**：AGP、Kotlin、Compose 等核心插件版本均通过 `libs.versions.toml` 的 `ref` 引用，新增依赖时应优先添加到 catalog 而非模块内硬编码。
- **CI/流水线**：根仓库未发现 `.github/workflows` 下的 CI 配置；仅在 `API_MODULE_AND_OLD_PROJECT_REPO/CPPlayer/.github/workflows/` 下存在历史 Android release 工作流，不属于当前 KMP 工程的构建流程。