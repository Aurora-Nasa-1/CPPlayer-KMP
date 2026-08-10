---
kind: dependency_management
name: Gradle Version Catalog + KMP 多模块依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - gradle.properties
---

## 1. 使用的系统与工具

本项目采用 **Gradle Kotlin DSL** 作为构建与依赖管理系统，核心依赖声明通过 **Version Catalog（`gradle/libs.versions.toml`）** 集中管理，并在根 `settings.gradle.kts` 中通过 `dependencyResolutionManagement` 统一仓库源。项目为 Kotlin Multiplatform (KMP) 工程，包含三个子模块：`:kmp-pro`（后端核心）、`:app`（跨平台 Compose UI 应用）、`:androidApp`（Android 壳），由根 `settings.gradle.kts` 聚合。

## 2. 关键文件

- `gradle/libs.versions.toml`：版本目录中心，定义所有第三方库的版本号、模块坐标与插件 ID。
- `settings.gradle.kts`：声明 `pluginManagement` 与 `dependencyResolutionManagement` 的仓库顺序（google → mavenCentral → gradlePluginPortal），并 include 三个子模块。
- `build.gradle.kts`（根）：仅通过 `alias(libs.plugins.*) apply false` 声明可用插件，不引入具体依赖。
- `gradle.properties`：配置 Gradle JVM 参数、AndroidX、代理（HTTP/HTTPS 经本地 7897 端口代理）。
- 各子模块 `build.gradle.kts`：通过 `alias(libs.*).dependencies` 引用 catalog 中的库。

## 3. 架构与约定

- **单一版本源**：所有第三方库版本集中在 `[versions]` 段，库引用统一使用 `version.ref = "xxx"` 形式，避免散落的硬编码版本号。
- **插件集中化**：AGP、Kotlin Multiplatform、Compose、Serialization 等插件 ID 在 `[plugins]` 段声明，子模块通过 `alias(libs.plugins.xxx)` 复用。
- **仓库策略**：`pluginManagement.repositories` 限定 Android/Google 相关插件仅从 google 仓库解析；`dependencyResolutionManagement.repositories` 对业务依赖开放 google() 与 mavenCentral()，未配置私有仓库或镜像。
- **KMP 多目标**：依赖按平台划分——如 `ktor-client-okhttp` 用于 Android/JVM，`kotlinx-coroutines-swing` 用于桌面端，`dbus-java`、`jna`、`jflac`、`mp3spi`、`jlayer` 等 JVM 专用音频/系统库仅在 jvmMain/desktopMain 中引入。
- **无锁文件**：根工程未检出 `gradle.lockfile` 或 `*.lock` 形式的依赖锁定文件，依赖解析依赖 Gradle 缓存与仓库版本约束。
- **代理网络**：通过 `systemProp.http(s).proxyHost/Port` 强制经本地 7897 端口代理访问外部仓库。

## 4. 约定与约束

- **版本必须走 catalog**：新增依赖应先在 `gradle/libs.versions.toml` 的 `[versions]` 中声明版本号，再在 `[libraries]` 中注册模块，子模块通过 `libs.xxx` 引用，禁止在 `build.gradle.kts` 中直接写死版本号。
- **插件必须用 alias**：根 `build.gradle.kts` 对所有已声明插件执行 `apply false`，子模块只能通过 `alias(libs.plugins.*)` 启用，防止版本漂移。
- **仓库白名单**：Android/Google 相关插件严格限制在 google 仓库解析，业务依赖允许 google + mavenCentral，未配置 jitpack、私有 Nexus/Artifactory 等额外源。
- **平台隔离**：JVM/Desktop 专属依赖（dbus-java、jna、jflac、mp3spi、jlayer）仅在对应 source set 引入，commonMain 不包含平台绑定库。
- **代理强制**：构建时始终通过本地 HTTP/HTTPS 代理（127.0.0.1:7897）拉取依赖，离线或直连场景需修改 `gradle.properties`。
- **无 vendor 目录**：未发现 vendored 第三方源码，所有依赖均通过远程仓库解析。
- **历史遗留独立工程**：`API_MODULE_AND_OLD_PROJECT_REPO/CPPlayer` 与 `3rd-CPPlayer-netcloudMusic-Muti`（Rust/Cargo 工程）是独立于本 KMP 工程的旧代码，各自维护自己的 `Cargo.lock` / `Cargo.toml`，与本 Gradle 依赖体系解耦。