# 快速上手

> 从零跑通一个可搜索、可播放的 Provider。不需要外部音乐平台或账号。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-21

## 1. 跑通仓库示例

仓库自带本地目录服务、9.5 秒 WAV 与一个 JS 音源。

| 步骤 | 命令 / 动作 |
|---|---|
| 1. 启动示例服务 | `node provider-sdk-kmp/examples/js/upstream.mjs` |
| 2. 另开终端打包 | `pwsh -File provider-sdk-kmp/examples/js/pack.ps1` |
| 3. 导入 | `artifacts/provider-sdk/kmp-catalog-0.1.0.zip` |
| 4. 验证 | 搜索 `all`，播放 |

服务只监听 `127.0.0.1:48482`。Android 真机手动验证需先 `adb reverse tcp:48482 tcp:48482`，否则设备的 localhost 不指向电脑。

## 2. 只替换业务

从 [`examples/js/index.js`](../../../provider-sdk-kmp/examples/js/index.js) 开始改。方法名、返回值形状不能改，`catalog` 换成自己的上游实现。

更小的三方法模板见 [`examples/js/template.js`](../../../provider-sdk-kmp/examples/js/template.js)，与同目录的 `manifest.json` 配对使用。

```javascript
globalThis.provider = {
  capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
  async search(request, context) {
    return catalog.search(request.keyword, request.limit, request.offset, context);
  },
  async get(request, context) {
    return catalog.get(request.id, context);
  },
  async resolve(request, context) {
    return catalog.resolve(request.id, request.quality, context);
  }
};
```

禁止项：

- 不返回 `{code, data}` 或宿主 JSON 封套。
- 不实现 `callApi`、`apiMap`、本地 HTTP 服务、端口分配。
- 不作为 Provider 契约返回上游原始响应体。

Kotlin 实现同一组方法，见 [GUIDE 第 3 节](GUIDE.md#3-kotlin)。

如果上游要求请求签名，JS 音源可以从 `context.crypto` 获取安全随机字节并计算 HMAC-SHA256。MD5 用于兼容旧协议。接口限制和签名示例见 [密码学与 Base64URL](JS.md#crypto)。

## 2.1 从模板生成

不想手写目录结构时，用脚手架按所选 operation 生成最小项目：

```powershell
node scripts/new-provider.mjs --kind js --with search,get,resolve --id my-provider --name "My Provider"
# 或
node scripts/new-provider.mjs --kind rust --with search,get,resolve --id my-provider --name "My Provider"
```

| 参数 | 说明 |
|---|---|
| `--kind js\|rust` | JS 单文件，或 Rust 原生 crate |
| `--with` | 操作名（`search,get,resolve`）或能力名（`tracks.search`）；`all` 生成全部业务操作 |
| `--out` | 输出目录，默认 `providers/<id>` |

只生成选中的方法。要关闭能力，删掉方法并从 `capabilities` 删除对应字符串即可；`manifest.json` 的 `capabilities` 只影响展示。Rust 项目还会生成 `Cargo.toml`、`src/lib.rs`、`.gitignore` 与构建说明。

打包统一用零依赖脚本：

```powershell
node provider-contract/pack.mjs providers/my-provider -o dist/my-provider-0.1.0.zip
```

它会先校验 manifest、入口 UTF-8、脚本 1 MiB / 原生 64 MiB 上限、DLL/SO/dylib 魔数、路径穿越与 `node_modules` / `.git`，再写出确定性 ZIP。原生包的 Cargo `target/` 构建目录会自动忽略。

## 3. 最小安装包

```text
my-provider.zip
├── manifest.json
└── index.js
```

```json
{
  "id": "my-provider",
  "name": "My Provider",
  "version": "1.0.0",
  "type": "js",
  "contract": "kmp-sdk-v1",
  "entryPoint": "index.js",
  "capabilities": ["search", "playback"],
  "sdkOrigins": ["https://music.example.com"]
}
```

| 约束 | 值 |
|---|---|
| 入口脚本编码 | UTF-8，严格校验 |
| 入口脚本大小 | ≤ 1 MiB |
| `sdkOrigins` | 必须覆盖实际请求 origin；省略等于禁止 HTTP |
| TypeScript | 先打包成单文件；不把 Node 或 `node_modules` 放进包 |
| 能力声明 | manifest 标签只作展示，运行期以 `capabilities` 方法为准 |
| 打包 | 用 `provider-contract/pack.mjs`，不要手工压缩整个工作目录 |

其余字段见 [MANIFEST](MANIFEST.md)。

## 4. 四条硬约定

| 约定 | 具体要求 |
|---|---|
| ID 不透明 | 永远是字符串；`001`、`A`、`a`、`A,a` 不得改写、拆分成数值 |
| 时间用毫秒 | `durationMs = 9500` 即 9.5 秒；批量详情保序、保留重复、逐项失败 |
| 流返回真实档位 | 给实际 `actualQuality` 与显式请求头；宿主不代猜音质、不加账号 Cookie |
| 未接入即 Unsupported | 不要为不支持的能力返回空列表，也不要回退旧协议 |

## 5. 当前范围

| 项 | 数量 / 状态 |
|---|---|
| 业务操作 | 164 个，含登录、目录读写、推荐、评论/私信、一起听、MV/视频、DJ、日历/动态、账号与 DRM |
| 只读目录 | 歌词、歌单、专辑、歌手（搜索 / 详情 / 曲目） |
| 未开放 | 通用原始端点透传、JSON batch |
| 真实上游 | 契约存在不等于某个音源已实现或已联调 |

完整清单见 [SDK_OPERATIONS](SDK_OPERATIONS.md)，剩余移植面见 [归档的迁移状态](../../history/SDK_MIGRATION_STATUS.md)。

## 6. 验证

```powershell
pwsh -File scripts/verify-provider-chain.ps1
```

| 约束 | 说明 |
|---|---|
| 平台 | 仅 Windows（内部调 `gradlew.bat`）；macOS / Linux 不能运行 |
| 判据 | 独立构建目录，检查 JUnit XML 的 failure / error / skipped 全为 0 |
| 覆盖 | SDK、统一层、导入回滚、缓存隔离、播放流头、Repository |
| 边界 | 定向回归不等于全仓测试通过；契约测试不等于上游联调 |

已打包的 Provider 还可以跑宿主侧一致性检查：

```powershell
.\gradlew.bat :core:providerConformance `
  -PproviderPackage=dist/my-provider-0.1.0.zip `
  -PproviderArgs="--json"
```

它走真实 `ModuleManager` → `ProviderManager` → `ProviderHost` 链路，检查 capabilities、search、get、批量保序、resolve 与未声明能力的 `Unsupported` 失败路径；`--xml` 可输出 JUnit。

不想先准备上游时，可以直接用仓库内的离线 fixture 验证工具链本身：

```powershell
node provider-contract/pack.mjs provider-sdk-kmp/examples/conformance-fixture -o dist/conformance-fixture.zip
.\gradlew.bat :core:providerConformance -PproviderPackage=dist/conformance-fixture.zip -PproviderArgs="--json"
```

预期 7 项全部 `pass`。该 fixture 也是最小的可运行 Provider，可复制后逐步替换成真实实现。

## 相关

- 契约与边界：[GUIDE](GUIDE.md)
- JS 差异：[JS](JS.md)
- 分域做法：[WORKFLOWS](WORKFLOWS.md)
- 清单字段：[MANIFEST](MANIFEST.md)
