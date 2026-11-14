# 归档

> 已被取代的设计与过程记录。保留用于查「当年为什么这么定」，不代表现行行为。
> 现行文档在 [docs/dev](../dev/ARCHITECTURE.md)。

| 文档 | 日期 | 取代者 |
|---|---|---|
| [PROVIDER_SDK_PROPOSAL](PROVIDER_SDK_PROPOSAL.md) | 2026-11-10 | 接口 → [GUIDE](../dev/providers/GUIDE.md)、[JS](../dev/providers/JS.md)；分域行为 → [WORKFLOWS](../dev/providers/WORKFLOWS.md)；清单 → [MANIFEST](../dev/providers/MANIFEST.md)；依赖方向 → [ARCHITECTURE](../dev/ARCHITECTURE.md) |
| [PROVIDER_TYPED_RUNTIME](PROVIDER_TYPED_RUNTIME.md) | 2026-11-10 | [ARCHITECTURE](../dev/ARCHITECTURE.md)、[GUIDE](../dev/providers/GUIDE.md) |
| [SDK_MIGRATION_STATUS](SDK_MIGRATION_STATUS.md) | 2026-11-10 | [GUIDE](../dev/providers/GUIDE.md)、[SDK_OPERATIONS](../dev/providers/SDK_OPERATIONS.md) |

## 阅读约定

| 约定 | 内容 |
|---|---|
| 不做链接修复 | 归档文档里提到的 `provider-sdk/`、`MusicApiServiceImpl.kt` 等路径是当时的仓库状态，相关文件已随兼容层删除 |
| 不据以开发 | 文中「仍回退旧 Provider」之类的迁移描述不是当前生产行为 |
| 引用方式 | 需要支撑当下结论时引现行文档，引归档要说明「当时」 |
