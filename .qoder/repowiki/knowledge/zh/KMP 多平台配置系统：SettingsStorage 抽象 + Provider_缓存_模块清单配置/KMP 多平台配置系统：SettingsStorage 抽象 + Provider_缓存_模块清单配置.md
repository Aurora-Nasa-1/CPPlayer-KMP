---
kind: configuration_system
name: KMP 多平台配置系统：SettingsStorage 抽象 + Provider/缓存/模块清单配置
category: configuration_system
scope:
    - '**'
source_files:
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/util/SettingsStorage.kt
    - kmp-pro/src/androidMain/kotlin/cp/player/kmp/util/AndroidSettingsStorage.kt
    - kmp-pro/src/desktopMain/kotlin/cp/player/kmp/util/DesktopSettingsStorage.kt
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CacheConfig.kt
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManifest.kt
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt
    - gradle/libs.versions.toml
    - gradle.properties
    - androidApp/src/main/kotlin/cp/player/app/MainActivity.kt
---

## 1. 使用的系统与方案

本仓库采用 **Kotlin Multiplatform expect/actual 模式** 实现跨平台配置与持久化，核心是 `SettingsStorage` 接口（commonMain）+ Android/Desktop 各自 actual 实现。此外还通过数据类、Gradle 版本目录和 JSON 清单文件管理运行时配置。

- **键值存储抽象**：`cp.player.kmp.util.SettingsStorage`（interface），提供 `getString/putString/remove/contains/clear` 五个方法，注释明确说明“替代 Android SharedPreferences”，用于 cookie、最近 Provider ID、缓存元信息等。
- **Android 实现**：`AndroidSettingsStorage` 基于 `SharedPreferences`，文件名由 `namespace` 参数决定；通过 `initKmpAndroidContext(context)` 注入应用级 Context，再由 `defaultSettingsStorage()` 工厂创建实例。
- **Desktop 实现**：`DesktopSettingsStorage` 使用内存 `ConcurrentHashMap` + `~/.kmp-pro/<namespace>.properties` 的 `java.util.Properties` 文件持久化，读写失败不阻塞主流程。
- **构建期配置**：`gradle/libs.versions.toml` 集中声明所有依赖版本（AGP、Kotlin、Coroutines、Ktor、Compose、Media3 等），`gradle.properties` 存放 Gradle JVM 参数、AndroidX 开关及本地代理设置。
- **运行时模块清单**：`ModuleManifest`（`@Serializable` data class）描述外部音源模块的 id/name/version/type(entryPoint/apiMap/updateUrl/supportedAbis/targetAppPackage)，是 Provider 插件化的配置契约。
- **缓存配置**：`CacheConfig` data class 暴露 `freshTtlMs/maxEntries/enableFallback/enableCache` 四个开关，控制 API 缓存新鲜度阈值、内存条目上限、错误回退开关与总开关。

## 2. 关键文件与包

| 文件 | 作用 |
|---|---|
| `kmp-pro/src/commonMain/kotlin/cp/player/kmp/util/SettingsStorage.kt` | 定义 `SettingsStorage` 接口与 `expect fun defaultSettingsStorage(namespace)` |
| `kmp-pro/src/androidMain/kotlin/cp/player/kmp/util/AndroidSettingsStorage.kt` | Android SharedPreferences 实现 + `initKmpAndroidContext` |
| `kmp-pro/src/desktopMain/kotlin/cp/player/kmp/util/DesktopSettingsStorage.kt` | Desktop 内存 Map + Properties 文件持久化实现 |
| `kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CacheConfig.kt` | 缓存 TTL、容量、回退开关的数据类 |
| `kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManifest.kt` | 外部 Provider 模块 JSON 清单结构 |
| `kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt` | 活跃 Provider 切换、端口分配、Cookie 隔离、`last_active_provider_id` 持久化 |
| `kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt` | 后端统一入口，组装 SettingsStorage/ProviderManager/ModuleManager/CachedMusicApiService |
| `gradle/libs.versions.toml` | 全仓依赖版本目录（[versions]/[libraries]/[plugins] 三段式） |
| `gradle.properties` | Gradle/JVM/AndroidX/代理等构建期属性 |
| `androidApp/src/main/kotlin/cp/player/app/MainActivity.kt` | Android 启动时调用 `initKmpAndroidContext` + `defaultSettingsStorage()` + `MusicBackend.init` |
| `app/src/desktopMain/kotlin/cp/player/app/Main.kt` | Desktop 启动时同样注入 settings 并 init MusicBackend |

## 3. 架构与约定

### 3.1 分层加载顺序
1. **构建期**：Gradle 通过 `libs.versions.toml` 统一管理依赖版本，`gradle.properties` 提供 JVM/AndroidX/代理等全局开关。
2. **进程启动**：Android `MainActivity.onCreate` / Desktop `Main` 先调用 `initKmpAndroidContext`（仅 Android），再 `defaultSettingsStorage()` 获取跨平台一致的 `SettingsStorage`，最后 `MusicBackend.init(context, settings)` 完成初始化。
3. **运行时**：`MusicBackend` 内部构造 `ProviderCookieStorage(settings)` → `ProviderManager(settings, cookieStorage)` → `ModuleManager(...)` → `MusicApiServiceImpl` → `CachedMusicApiService(delegate, cache, config=CacheConfig())`，形成一条以 `SettingsStorage` 为根的配置链。
4. **状态恢复**：`ProviderManager.restoreLastProvider` 从 `settings.getString("last_active_provider_id")` 读取上次活跃的 Provider ID 并自动恢复。

### 3.2 配置来源与优先级
- **硬编码默认值**：`CacheConfig` 默认 `freshTtlMs=5min`、`maxEntries=64`、`enableFallback=true`、`enableCache=true`；`ProviderManager` 默认端口 `3000`、最大尝试次数 `20`。
- **用户持久化**：通过 `SettingsStorage` 写入的 key 包括 `cookie_<providerId>`（按 Provider 隔离）、`last_active_provider_id`（最近活跃 Provider）。
- **外部模块清单**：`ModuleManifest` 由外部 zip 模块提供，JSON 反序列化后驱动 JNI/binary/http 三种 Provider 类型。
- **平台差异**：Android 用 SharedPreferences，Desktop 用 `~/.kmp-pro/<namespace>.properties`，两者对上层完全透明。

### 3.3 设计决策
- **单一抽象**：所有持久化都走 `SettingsStorage`，避免 commonMain 直接依赖 Android SDK 或 Java IO。
- **命名空间隔离**：`defaultSettingsStorage(namespace = "cp_player_prefs")` 允许不同子系统使用独立文件/SharedPreferences 名。
- **容错优先**：Desktop 持久化失败 catch 异常后继续运行；Android 未注入 Context 时 `error(...)` 快速失败，保证调试可见性。
- **Provider 插件化**：通过 `ModuleManifest.type ∈ {"jni", "binary", "http"}` 动态选择加载方式，`supportedAbis` 支持按 CPU 架构选择 native lib。

## 4. 约定与约束

- **Android 启动约束**：必须在 `Application.onCreate` 中调用 `initKmpAndroidContext(context)`，否则 `defaultSettingsStorage()` 会抛 `error("Call initKmpAndroidContext(context) in Application.onCreate() first")`。这是代码强制的运行时约束。
- **Cookie 隔离约定**：`ProviderCookieStorage` 固定使用 `cookie_<providerId>` 作为 key 前缀，新增 Provider 必须遵守此命名规则。
- **最近 Provider 持久化**：`ProviderManager.switchProvider` 在 `save=true` 时写入 `last_active_provider_id`，重启后通过 `restoreLastProvider` 恢复。
- **端口冲突处理**：`PlatformSupport.findAvailablePort(defaultPort, MAX_PORT_ATTEMPTS=20)` 自动探测可用端口，全部占用时返回 null 并记录日志。
- **缓存开关**：`CacheConfig.enableCache=false` 时所有请求直通底层 Provider，绕过内存缓存；`enableFallback=false` 时关闭 ERROR 时的多 Provider 容灾。
- **Gradle 版本集中管理**：所有第三方库版本集中在 `gradle/libs.versions.toml` 的 `[versions]` 段，通过 `version.ref = "xxx"` 引用，禁止在模块 build.gradle 中硬编码版本号。
- **模块清单字段约束**：`ModuleManifest` 的 `type` 必须为 `"jni"`、`"binary"` 或 `"http"`；`supportedAbis` 为空/null 时表示单架构模块（向后兼容旧格式）；`targetAppPackage` 仅在 Android 端生效，Desktop 忽略。
