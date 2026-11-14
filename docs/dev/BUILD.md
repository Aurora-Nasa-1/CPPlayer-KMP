# 构建与验证

> 环境、常用任务、验证判据、并行会话隔离。适用读者：贡献者、维护者。

## 1. 环境

| 依赖 | 版本 / 说明 |
|---|---|
| JDK | 21+，推荐 JetBrains Runtime 21 |
| Android SDK | API 35+ |
| Node.js | 22+，仅契约生成与 JS 测试需要 |
| PowerShell | 7+，验证脚本需要；脚本内部调用 `gradlew.bat`，仅 Windows |
| Rust | 仅重建 FlickEngine 原生库时需要，见 [FLICK_ENGINE](FLICK_ENGINE.md) |

## 2. 常用任务

| 任务 | 命令 |
|---|---|
| 桌面运行 | `./gradlew :app:run` |
| 编译后端 | `./gradlew :core:compileKotlinDesktop` |
| 编译后端（Android） | `./gradlew :core:compileAndroidMain` |
| 编译前端 | `./gradlew :app:compileKotlinDesktop` |
| 单元测试 | `./gradlew :core:desktopTest`、`./gradlew :app:desktopTest` |
| 定向链路回归 | `pwsh -File scripts/verify-provider-chain.ps1` |
| 契约生成 | `node provider-contract/generate.mjs` |
| 契约一致性 | `node provider-contract/generate.mjs --check` |
| Windows 安装包 | `./gradlew :app:packageWindowsVelopack` |
| Android 包 | `./gradlew :app-android:assembleDebug` |

任务名注意：是 `:core:compileKotlinDesktop`（不是 `compileDesktopKotlin`）、`:core:compileAndroidMain`（不是 `compileKotlinAndroid`，后者不存在）。根项目名是 `CPPlayer`，不是目录名。

## 3. 验证判据

| 项 | 判据 |
|---|---|
| 编译 | `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop` 均通过 |
| 测试 | 只认 `**/test-results/**/TEST-*.xml`；`skipped="0"` 才算跑过 |
| 跳过 | 端到端测试用 `assumeTrue` 跳过时同样 BUILD SUCCESSFUL |
| 版式 | 编译与单测量不到宽度与对齐，必须离屏渲染出图核对 |
| 模块结论 | 「某模块编译不过」要落到具体文件与行号，不从日志尾部猜 |

`commonMain` 是整个源集一起编译：一个文件坏了，源集内所有文件一起报错。看第一个报错的文件，不要「谁报错谁错」。

不要用 `| head` 截断 Gradle 输出判断测试结果。定向回归不等于全仓测试通过；契约测试不等于上游联调。

## 4. 并行会话隔离

本仓库常有多个会话同时改同一工作区，Gradle 锁会被抢占。

| 问题 | 做法 |
|---|---|
| `.gradle/<version>/fileHashes/fileHashes.lock` 被占用 | 加 `--project-cache-dir=<会话专属>` |
| 仅换 project-cache 不够 | 再用 **Groovy** init script 把 `layout.buildDirectory` 换到 `build-verify-<后缀>/<module>` |
| init script 语言 | 必须 Groovy；`.kts` 里 `allprojects { p -> … }` 会挑错重载 |
| 共享 verify 目录 | 本身也会被占；每个会话用自己的后缀 |
| 排除在途破损测试 | 必须写在 `afterEvaluate` / `projectsEvaluated`；挂 `plugins.withId(...multiplatform)` 时源集还没创建，静默不生效 |
| init script 求值阶段 | `gradle.rootProject` 不可用，用 `allprojects { p -> p.rootProject }` |
| 用完清理 | 删掉 `build-verify*/`、`.gradle-verify*/`、init script；留着会永久跳过被 exclude 的测试 |

## 5. 构建竞态

有应用正在 `desktopRun` 时，不要对 `app` / `core` 跑标准编译。

| 项 | 内容 |
|---|---|
| 原因 | run 任务把 `app/build/classes/kotlin/desktop/main`、`core/build/classes/...` 直接挂在运行时 classpath，不是快照 jar |
| 后果 | 编译重写这些目录；运行中的 JVM 懒加载尚未用到的类时报 `NoClassDefFoundError`，Compose 组合崩，窗口 dispose 再连锁报 `layout state is not idle` |
| 判据 | 类在源码与产物里都在 + 崩溃栈行号超出当前源文件行数 + 类文件时间戳与崩溃时刻吻合 |
| 处理 | 重启应用；不要改代码。verify 构建重定向了 buildDirectory，不受影响 |

不要 `rm -rf` 构建目录：会撞安全删除闸（>50 文件被拦），命令静默失败。强制重编译用 `./gradlew <task> --rerun`。

## 6. 网络与代理

| 现象 | 处理 |
|---|---|
| 本地探针拿到 502 | 沙箱设了 `http_proxy=127.0.0.1:64222`，原生 HTTP（rodio / reqwest）也走它；用 `env -u http_proxy -u https_proxy …` |
| 测试 JVM 访问本地服务失败 | 设 `no_proxy=127.0.0.1,localhost` |
| 后台 HTTP server 一启动就消失 | server 与客户端放进同一条命令，父 shell 退出会带走子进程 |

## 相关

- 架构与源集：[ARCHITECTURE](ARCHITECTURE.md)
- 发布流程：[RELEASE](RELEASE.md)
- 代码与 UI 规范：[CONTRIBUTING](../../CONTRIBUTING.md)
