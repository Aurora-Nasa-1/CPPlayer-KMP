---
kind: error_handling
name: KMP 后端结果类型 + Rust thiserror 错误体系的双层错误处理
category: error_handling
scope:
    - '**'
source_files:
    - kmp-pro/src/commonMain/kotlin/cp/player/kmp/BackendState.kt
    - app/src/commonMain/kotlin/cp/player/app/AppModel.kt
    - app/src/androidMain/kotlin/cp/player/app/platform/PlatformActions.android.kt
    - kmp-pro/src/jvmMain/kotlin/cp/player/kmp/provider/BinaryProvider.kt
    - API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/error.rs
    - API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/request.rs
    - API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/server/middleware.rs
    - API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/tests/error.rs
---

## 1. 整体方案

本仓库包含两个语言域，各自采用不同的错误处理体系：

- **Kotlin Multiplatform（主工程）**：通过 `sealed class` 定义类型安全的返回封装 `BackendResult<T>` 与 `ImportResult`，配合 `runCatching` / `try { ... } catch (...)` 在 UI 层捕获异常并转换为可展示的错误信息。
- **Rust 子模块（`API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti`）**：使用 `thiserror::Error` 定义 `NcmError` 枚举，并通过 `std::result::Result<T, NcmError>` 作为所有 API 函数的统一返回类型；HTTP 中间件以 Axum 中间件形式集中处理限流等横切错误。

两者通过 JVM 侧的 `BinaryProvider` 进程间通信桥接：Kotlin 调用独立 Rust 二进制，Rust 返回 JSON 字符串，Kotlin 再解析为业务结果。因此 Kotlin 侧不直接持有 Rust 异常，而是依赖 JSON 中的 `code`/`msg` 字段进行错误传播。

## 2. 关键文件与位置

| 领域 | 文件 | 作用 |
|---|---|---|
| Kotlin 核心 | `kmp-pro/src/commonMain/kotlin/cp/player/kmp/BackendState.kt` | 定义 `BackendState`、`BackendResult<T>`、`ImportResult` 三个 sealed 类型 |
| Kotlin UI | `app/src/commonMain/kotlin/cp/player/app/AppModel.kt` | 调用后端并消费 `BackendResult`/`ImportResult`，将错误写入 `lastSwitchError` |
| Kotlin 平台适配 | `app/src/androidMain/kotlin/cp/player/app/platform/PlatformActions.android.kt` | Android 特有 I/O 用 `try/catch` 包裹，失败时返回空或默认值 |
| Kotlin Provider | `kmp-pro/src/jvmMain/kotlin/cp/player/kmp/provider/BinaryProvider.kt` | 启动外部 Rust 二进制，失败时记录 `loadError` 并以 JSON `{"code":500,"msg":...}` 返回 |
| Rust 错误定义 | `API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/error.rs` | `NcmError` 枚举 + `from_api` 构造器 + `type Result = std::result::Result<T, NcmError>` |
| Rust 请求层 | `API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/request.rs` | 将 HTTP 状态码映射为 `NcmError`，特殊码（201/400/502/800/801/802/803）视为成功 |
| Rust 中间件 | `API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/src/server/middleware.rs` | CORS 与基于 IP 的 `RateLimiter` 中间件，超限返回 429 JSON |
| Rust 测试 | `API_MODULE_AND_OLD_PROJECT_REPO/3rd-CPPlayer-netcloudMusic-Muti/tests/error.rs` | 验证 `from_api` 对 301/400/503/其他码的分类逻辑 |

## 3. 架构与约定

### Kotlin 侧：`BackendResult<T>` 三态模型

`BackendResult<T>` 是跨模块统一的错误返回类型，区分三种情形：
- `Success(data)` — 正常结果
- `Error(message, code?, cause?)` — 系统故障（网络、Provider 未就绪、解析异常），`cause` 仅用于日志
- `Unsupported(message)` — 功能缺失（音源不支持该 API），UI 应提示“该音源不支持此功能”而非“出错了”（见注释）

提供 `fold(onSuccess, onError, onUnsupported)` 强制穷举分支，以及 `getOrNull/getOrThrow/getOrDefault` 便捷访问。

`BackendState` 描述后端生命周期：`Uninitialized → Initializing → Ready/NoProvider/Error`，其中 `Error(message)` 专门表示初始化过程抛出异常（区别于无模块的 `NoProvider`）。

`ImportResult` 区分导入成功后的两种后续状态：`Activated(provider)`（自动激活为新 Provider）与 `Loaded(provider)`（已有活跃 Provider 未切换），以及 `Failed(message)`。

### Rust 侧：`NcmError` 枚举 + `from_api` 工厂

`NcmError` 覆盖 HTTP 错误、API 业务错误、认证缺失、参数错误、加密错误、JSON 错误、超时、限流、未知错误等场景。`from_api(code, msg)` 根据网易云 API 返回码分类：301→`AuthRequired`、400→`InvalidParam`、503→`RateLimited`，其余归入 `Api { code, msg }`。

`request.rs` 中 `ApiClient.request` 将响应体中的 `code` 字段（或 HTTP 状态码）标准化后判断：等于 200/302 返回 `Ok(ApiResponse)`，否则调用 `NcmError::from_api` 构造具体错误。

### 中间件错误

Axum 侧的 `rate_limit_middleware` 按 IP 统计窗口内请求数，超过阈值返回 `StatusCode::TOO_MANY_REQUESTS` 与固定 JSON `{"code":429,"msg":"Too many requests, please slow down"}`。

### 桥接层策略

`BinaryProvider.callApi` 将 Kotlin 调用包装为 HTTP POST 到本地端口，若 Rust 进程不可用则直接返回 `{"code":500,"msg":"Binary not ready: ..."}`；网络异常被捕获后同样转为 JSON 错误字符串。上层 Kotlin 代码需自行解析该 JSON 并映射为 `BackendResult`。

## 4. 约定与约束

- Kotlin 层**禁止裸抛异常给 UI**：所有可失败操作统一返回 `BackendResult<T>`，UI 通过 `when` 或 `fold` 穷举处理成功、错误、不支持三类分支。
- `BackendResult.Error.cause` 仅用于日志/调试，不应直接展示给用户；用户可见消息来自 `message` 字段。
- `BackendResult.Unsupported` 与 `Error` 语义分离：前者表示“功能缺失”，后者表示“系统故障”，UI 文案必须不同。
- Rust 层所有 API 函数签名统一返回 `Result<ApiResponse>`（即 `std::result::Result<ApiResponse, NcmError>`），调用方不得吞掉错误。
- 网易云 API 的特殊状态码集合（201/400/502/800/801/802/803）在 `request.rs` 中被显式视为成功，新增状态码需同步修改该集合。
- 限流中间件以 IP 为单位维护滑动窗口计数，超限后返回 429，调用方应据此实现退避重试。
- Android 平台适配层对可能抛异常的 I/O 操作使用 `try/catch` 包裹并降级为空结果，避免崩溃传播到 Compose 层。
- 单元测试 `tests/error.rs` 断言 `from_api` 的分类行为，新增错误码分类时需补充对应用例。