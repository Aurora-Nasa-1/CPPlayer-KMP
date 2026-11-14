# 契约维护

> 跨语言契约与其参考文档的生成源头。适用读者：改 SDK 的人。
> Provider 作者入口见 [GUIDE](../docs/dev/providers/GUIDE.md)。

## 1. 文件职责

| 文件 | 责任 |
|---|---|
| `schema.mjs` | 契约版本、记录字段顺序、默认值、操作 tag、分组、能力名 |
| `documentation.mjs` | 每个业务操作、每条记录、公共字段含义与逐记录覆盖 |
| `generate.mjs` | Kotlin / TS 声明、编解码、派发、宿主与统一层转发、夹具、参考页 |
| `documentation.test.mjs` | 跨语言签名与文档覆盖、宿主选择器一致性、文档链接 |

## 2. 改动流程

```powershell
node provider-contract/generate.mjs
node provider-contract/generate.mjs --check
node --test provider-contract/documentation.test.mjs provider-sdk-kmp/js/test.mjs
npm ci --prefix provider-sdk-kmp/js --ignore-scripts
npm run --prefix provider-sdk-kmp/js check:types
```

| 命令 | 判据 |
|---|---|
| `generate.mjs` | 重写全部生成物 |
| `--check` | 文档元数据缺失 / 过期或生成物漂移即失败 |
| `documentation.test.mjs` | 全部 161 个业务方法与 155 个 DTO 都有文档 |

生成器维护 `MusicProvider.kt` / `CatalogProvider.kt` 的方法 KDoc，但保留手写的实现与默认方法体。运行期与生命周期声明仍是手写。

## 3. 兼容规则

| 规则 | 内容 |
|---|---|
| 既有布局 | 不改字段顺序、类型与操作 tag；记录的二进制序列化是位置式 |
| 新增方法 | 必须保留 `Unsupported` 默认实现 |
| 改线格式 | 需要显式的兼容 / 版本决策 |
| 描述边界 | 描述 Provider 责任与宿主校验要分开写 |

`AuthActionRequest.countryCode` 在 Kotlin / TS DTO 中存在，但不追加到 Native v1 的位置式请求字节里：native 编解码器把显式值镜像进既有的 auth-only `extraJson` 字段，缺省时该字段不变，Provider 保持原有默认（含适用时的 `86`）。

## 4. 生成产物

| 产物 | 位置 |
|---|---|
| 操作参考 | [docs/dev/providers/SDK_OPERATIONS.md](../docs/dev/providers/SDK_OPERATIONS.md) |
| 字段参考 | [docs/dev/providers/SDK_TYPES.md](../docs/dev/providers/SDK_TYPES.md) |
| 分域实现 | [docs/dev/providers/WORKFLOWS.md](../docs/dev/providers/WORKFLOWS.md) |

新约束要先对照 `ProviderHost` / `ExtendedOperationValidation` 与 runtime 实现，确认真的被强制，再写成文档。

声明改动后要跑仓库要求的桌面编译。两个 PowerShell 验证脚本仅 Windows 可用（调用 `gradlew.bat`），它们包含文档测试并保留 JUnit XML；文档测试不替代上游 / Provider 集成测试。

## 相关

- 构建与验证：[BUILD](../docs/dev/BUILD.md)
- Provider 契约：[GUIDE](../docs/dev/providers/GUIDE.md)
- SDK 源码说明：[provider-sdk-kmp/README](../provider-sdk-kmp/README.md)
