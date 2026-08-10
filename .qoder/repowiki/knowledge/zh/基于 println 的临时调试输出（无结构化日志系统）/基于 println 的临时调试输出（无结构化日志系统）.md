---
kind: logging_system
name: 基于 println 的临时调试输出（无结构化日志系统）
category: logging_system
scope:
    - '**'
source_files:
    - app/src/commonMain/kotlin/cp/player/app/ui/model/HomeScreenModel.kt
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt
    - kmp-pro/src/jvmMain/kotlin/cp/player/kmp/provider/BinaryProvider.kt
    - kmp-pro/src/jvmMain/kotlin/cp/player/kmp/provider/JniProvider.kt
    - gradle/libs.versions.toml
---

## 现状概述

本仓库**没有引入任何第三方日志框架或统一的日志子系统**。整个 KMP 工程（`app`、`kmp-pro`）以及被保留的 Rust 示例代码中，所有“日志”行为均通过标准库的 `println!` / `println` 直接输出到标准输出，属于开发期的临时调试手段。

## 具体使用位置与模式

- **KMP 应用层 (`app`)**：`HomeScreenModel.kt` 在初始化、刷新、加载推荐歌曲等关键路径上插入大量 `println("[HomeScreenModel] ...")`，用于跟踪 UI Model 状态流转与网络调用结果。
- **KMP 后端核心 (`kmp-pro`)**：
  - `ProviderManager.kt`：端口探测失败时打印 `[ProviderManager] 端口 xxx~xxx 全被占用`。
  - `BinaryProvider.kt`：进程启动成功/失败时分别打印 `[BinaryProvider] Started ...` 和 `[BinaryProvider] Failed to start: ...`。
  - `JniProvider.kt`：通过 `println("[JniProvider] $msg")` 透传底层消息。
- **Rust 侧（`API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti`）**：示例程序使用 `println!` / `eprintln!` 输出搜索、详情、歌词等调试信息；构建脚本 `build.rs` 用 `println!("cargo:rerun-if-changed=...")` 触发增量编译。

## 架构与约定

1. **无统一 Logger 抽象**：不存在 `Logger`、`Log`、`ILogger` 等接口或单例，各模块自行调用 `println`。
2. **前缀式分类**：通过方括号包裹的类名作为日志前缀（如 `[HomeScreenModel]`、`[BinaryProvider]`、`[JniProvider]`、`[ProviderManager]`），便于在 stdout 中按来源过滤。
3. **无日志级别**：全部使用同一输出通道，没有 debug/info/warn/error 分级。
4. **无结构化字段**：日志为纯文本拼接，未采用 JSON 或其他结构化格式。
5. **无 Sink/路由配置**：没有配置文件或初始化代码将日志重定向到文件、远程收集器或平台特定输出。
6. **依赖声明中无日志库**：`gradle/libs.versions.toml` 未定义任何 logging 相关版本或库条目，各 `build.gradle(.kts)` 也未引入 log4k、kotlin-logging、slf4j、timber 等常见 Kotlin/Android 日志依赖。

## 约束与规则

- 当前代码库**未强制**任何日志规范；新增日志点均为开发者自由添加的 `println`。
- 由于输出目标为标准输出，在 Android 端可通过 Logcat 查看，在 Desktop 端则输出到控制台——这决定了该方式仅适合本地调试，不适合生产环境。
- Rust 侧示例代码同样只使用 `println!` / `eprintln!`，未集成 `tracing`、`log`、`env_logger` 等 crate。

## 结论

该项目目前处于**无正式日志系统**的状态，所有可观测性输出都是散落的 `println` 调试语句。若需要生产级日志能力，需先选型（如 kotlin-logging + slf4j/timber for Android，或 tracing for Rust），再建立统一的初始化入口、级别策略与 sink 配置。