新项目可能有些实现不正确，参考旧项目和音源提供模块源代码。

两份参考代码都在 `reference/` 下，**只读，不参与构建**（改它们不影响产物）：

- `reference/cp-player-legacy/` —— 原 Android 项目（Kotlin + Media3 + Rust 音频引擎）。
  移植时的对照基准，尤其用于核对 seek / 切歌 / 本地文件打开等行为差异。
- `reference/netease-module-rust/` —— 第三方音源模块。`src/api/` 下是 400+ 个
  网易云 API 实现，`src/server/` 与 `src/util/` 提供本地 HTTP 服务与 JNI 入口。

当前模块职责与依赖规则见 `docs/ARCHITECTURE.md`；结构迁移计划见
`docs/RESTRUCTURE_PLAN.md`。
