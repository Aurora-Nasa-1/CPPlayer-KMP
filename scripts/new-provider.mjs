/**
 * Generate a minimal kmp-sdk-v1 provider.
 *
 * Usage:
 *   node scripts/new-provider.mjs --kind js|rust --with search,get,resolve \
 *     [--id my-provider] [--name "My Provider"] [--out providers/my-provider]
 *
 * Only the selected operations are generated. The capability list in code is
 * the runtime switch; the manifest's capabilities field is display metadata.
 */
import { existsSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import schema from "../provider-contract/schema.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,127}$/;
const business = Object.entries(schema.operations).filter(([, operation]) => operation.capability);
const operationsByName = new Map(business);
const operationsByCapability = new Map();
for (const [name, operation] of business) {
  const list = operationsByCapability.get(operation.capability) ?? [];
  list.push([name, operation]);
  operationsByCapability.set(operation.capability, list);
}

function fail(message) {
  throw new Error(message);
}

function parseArgs(argv) {
  const options = {
    kind: "js",
    with: "search,get,resolve",
    id: "my-provider",
    name: "My Provider",
    out: null,
    sdkPath: null,
  };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    const read = () => {
      const value = argv[++index];
      if (!value) fail(`${arg} requires a value`);
      return value;
    };
    switch (arg) {
      case "--kind":
      case "-k":
        options.kind = read();
        break;
      case "--with":
      case "-w":
        options.with = read();
        break;
      case "--id":
        options.id = read();
        break;
      case "--name":
        options.name = read();
        break;
      case "--out":
      case "-o":
        options.out = read();
        break;
      case "--sdk-path":
        options.sdkPath = read();
        break;
      case "--help":
      case "-h":
        options.help = true;
        break;
      default:
        fail(`Unknown option: ${arg}`);
    }
  }
  return options;
}

function usage() {
  console.log(`Usage:
  node scripts/new-provider.mjs --kind js|rust --with search,get,resolve \\
    [--id my-provider] [--name "My Provider"] [--out providers/my-provider]

--with accepts operation names (search, get, resolve) or capability names
(tracks.search). Run with --with all to generate every business operation.`);
}

function selectOperations(value) {
  const tokens = value.split(",").map((token) => token.trim()).filter(Boolean);
  if (tokens.length === 0) fail("--with must select at least one operation");
  if (tokens.length === 1 && tokens[0] === "all") return business.slice();
  const selected = new Map();
  for (const token of tokens) {
    if (token === "all") {
      business.forEach(([name, operation]) => selected.set(name, operation));
      continue;
    }
    const operation = operationsByName.get(token);
    if (operation) {
      selected.set(token, operation);
      continue;
    }
    const byCapability = operationsByCapability.get(token);
    if (byCapability) {
      byCapability.forEach(([name, op]) => selected.set(name, op));
      continue;
    }
    const suggestions = business
      .map(([name]) => name)
      .filter((name) => name.includes(token) || token.includes(name))
      .slice(0, 8);
    fail(
      `Unknown operation or capability: ${token}` +
      (suggestions.length > 0 ? `\nDid you mean: ${suggestions.join(", ")}` : ""),
    );
  }
  return [...selected.entries()];
}

function snake(name) {
  return name
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1_$2")
    .toLowerCase();
}

function upper(name) {
  return snake(name).toUpperCase();
}

const RUST_KEYWORDS = new Set([
  "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false",
  "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut",
  "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait",
  "true", "type", "unsafe", "use", "where", "while", "async", "await", "dyn",
]);

function rustMethod(name) {
  const value = snake(name);
  return RUST_KEYWORDS.has(value) ? `r#${value}` : value;
}

const JS_IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

function jsMethod(name) {
  return JS_IDENTIFIER.test(name) ? name : `[${JSON.stringify(name)}]`;
}

function capabilityConstant(capability) {
  const first = business.find(([, operation]) => operation.capability === capability);
  if (!first) fail(`Unknown capability: ${capability}`);
  return upper(first[0]);
}

function manifestFor(options, selected) {
  const capabilities = [...new Set(selected.map(([, operation]) => operation.capability))];
  return {
    id: options.id,
    name: options.name,
    version: "0.1.0",
    type: options.kind === "rust" ? "native" : "js",
    contract: "kmp-sdk-v1",
    entryPoint: options.kind === "rust" ? "provider.dll" : "index.js",
    ...(options.kind === "rust" ? { supportedAbis: ["x86_64"] } : {}),
    capabilities,
    sdkOrigins: ["https://api.example.com"],
  };
}

function jsSource(selected) {
  const capabilities = [...new Set(selected.map(([, operation]) => operation.capability))];
  const methods = selected.map(([name, operation]) => {
    return `    async ${jsMethod(name)}(request, context) {
        // TODO: implement ${operation.capability}.
        // Delete this method and its capability entry to turn the feature off.
        throw failure("Unsupported", ${JSON.stringify(`${operation.capability} is not implemented yet`)});
    },`;
  }).join("\n\n");
  return `/*
 * Generated by scripts/new-provider.mjs.
 *
 * The capabilities array is the runtime switch. Keep only methods you serve and
 * list the same capability strings here. The manifest's capabilities field is
 * display metadata only.
 */
globalThis.provider = {
    capabilities: [${capabilities.map((capability) => JSON.stringify(capability)).join(", ")}],

${methods}
};

function failure(category, message) {
    return Object.assign(Error(message), { category });
}
`;
}

function rustSource(selected) {
  const capabilities = [...new Set(selected.map(([, operation]) => operation.capability))];
  const methods = selected.map(([name, operation]) => {
    return `    fn ${rustMethod(name)}(&mut self, request: ${operation.request}, context: &ProviderContext) -> ProviderResult<${operation.response}> {
        let _ = (request, context);
        // TODO: implement ${operation.capability}.
        unsupported()
    }`;
  }).join("\n\n");
  return `//! Generated by scripts/new-provider.mjs.
//!
//! The capability list is the runtime switch. Remove a method and its
//! capability to turn the feature off; undeclared methods return Unsupported.
use cpplayer_provider_sdk::*;

struct MyProvider;

impl MusicProvider for MyProvider {
    fn capabilities(&self, _context: &ProviderContext) -> Vec<&'static str> {
        vec![${capabilities.map((capability) => `Capability::${capabilityConstant(capability)}`).join(", ")}]
    }

${methods}
}

fn create(_context: &ProviderContext) -> Box<dyn MusicProvider> {
    Box::new(MyProvider)
}

export_provider!(create);
`;
}

function relativeCommand(from, to) {
  const value = relative(from, to).split("\\").join("/");
  if (isAbsolute(value)) return value;
  return value.startsWith(".") ? value : `./${value}`;
}

function jsReadme(options, selected, packScript) {
  const capabilities = [...new Set(selected.map(([, operation]) => operation.capability))];
  return `# ${options.name}

Generated JS provider for \`kmp-sdk-v1\`.

| 项 | 值 |
|---|---|
| ID | \`${options.id}\` |
| 能力 | ${capabilities.map((capability) => `\`${capability}\``).join("、")} |
| 入口 | \`index.js\` |

## 开发

1. 在 \`index.js\` 里实现选中的方法，替换 \`TODO\`。
2. 只保留实际提供的方法，并从 \`capabilities\` 删除对应字符串。运行期以这份列表为准；\`manifest.json\` 的 \`capabilities\` 只影响展示。
3. 把 \`manifest.json\` 的 \`sdkOrigins\` 改成真实上游 origin。省略等于禁止 HTTP。

\`\`\`powershell
node ${packScript} . -o dist/${options.id}-0.1.0.zip
\`\`\`

把 ZIP 导入 CPPlayer。打包器会先校验 manifest、入口 UTF-8、1 MiB 上限、路径穿越与依赖目录。

## 约束

- ID 始终是不透明字符串，不要 \`Number()\`、不要按逗号拆分。
- 时间字段用毫秒；\`durationMs = 9500\` 表示 9.5 秒。
- 流返回真实 \`actualQuality\`，不要把请求档位当成实际上游档位。
- 不支持的能力不要返回空列表；删掉方法即可，宿主会得到 \`Unsupported\`。
`;
}

function rustReadme(options, selected, packScript, sdkPath) {
  const capabilities = [...new Set(selected.map(([, operation]) => operation.capability))];
  return `# ${options.name}

Generated Rust provider for \`kmp-sdk-v1\`. The SDK owns Wire encoding, operation
dispatch, session handles, cancellation, and both the JNI and C ABI exports.

| 项 | 值 |
|---|---|
| ID | \`${options.id}\` |
| 能力 | ${capabilities.map((capability) => `\`${capability}\``).join("、")} |
| 入口 | \`provider.dll\`（按平台改名） |
| SDK | \`${sdkPath}\` |

## 开发

\`\`\`rust
impl MusicProvider for MyProvider {
    fn capabilities(&self, _context: &ProviderContext) -> Vec<&'static str> {
        vec![${capabilities.map((capability) => `Capability::${capabilityConstant(capability)}`).join(", ")}]
    }

    // Implement only the methods you serve. Every method has an Unsupported default.
}
\`\`\`

- 字段名是 Rust \`snake_case\`；Wire 是位置式，字段顺序由生成的 DTO 固定。
- DTO 的 \`Failure\` 在 Rust 里叫 \`WireFailure\`，避免与作者侧错误类型 \`Failure\` 冲突。
- \`export_provider!(create)\` 一次导出 5 个 \`cpp_*\` 与 4 个 JNI 符号。不要手写这些符号。
- 保留 \`Cargo.lock\`，保证构建可复现。

### 构建

\`\`\`powershell
cargo build --release
\`\`\`

Windows 上如果链接器不在 PATH，从「x64 Native Tools Command Prompt for VS」里构建，
或先在当前 cmd 会话执行：

\`\`\`powershell
call "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat"
cargo build --release
\`\`\`

把生成的动态库复制到包根目录：

| 平台 | 源 | manifest \`entryPoint\` |
|---|---|---|
| Windows x64 | \`target/release/${options.id.replace(/[^a-z0-9_]/g, "_")}.dll\` | \`provider.dll\` |
| Linux x64 | \`target/release/lib${options.id.replace(/[^a-z0-9_]/g, "_")}.so\` | \`provider.so\` |
| macOS | \`target/release/lib${options.id.replace(/[^a-z0-9_]/g, "_")}.dylib\` | \`provider.dylib\` |

同时把 \`manifest.json\` 的 \`entryPoint\` 与 \`supportedAbis\` 改成实际平台。

### 打包

\`\`\`powershell
node ${packScript} . -o dist/${options.id}-0.1.0.zip
\`\`\`

打包器会检查动态库魔数（PE / ELF / Mach-O）、64 MiB 入口上限与路径安全。

## 能力开关

删掉一个方法，并从 \`capabilities\` 删除对应字符串，即关闭该能力。不要用
\`manifest.json\` 的 \`capabilities\` 当运行期开关。
`;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }
  if (!["js", "rust"].includes(options.kind)) fail("--kind must be js or rust");
  if (!ID_PATTERN.test(options.id) || options.id.includes("..") || options.id === "local") {
    fail("--id must match [a-z0-9][a-z0-9._-]{0,127} and must not be local");
  }
  if (!options.name.trim()) fail("--name must not be blank");
  const selected = selectOperations(options.with);
  const output = resolve(options.out ?? join("providers", options.id));
  if (existsSync(output) && readdirSync(output).length > 0) {
    fail(`Output directory is not empty: ${output}`);
  }
  mkdirSync(output, { recursive: true });
  const manifest = manifestFor(options, selected);
  writeFileSync(join(output, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
  const packScript = relativeCommand(output, join(repoRoot, "provider-contract", "pack.mjs"));

  if (options.kind === "js") {
    writeFileSync(join(output, "index.js"), jsSource(selected), "utf8");
    writeFileSync(join(output, "README.md"), jsReadme(options, selected, packScript), "utf8");
  } else {
    const crate = options.id.replace(/[^a-z0-9_]/g, "_").replace(/^([0-9])/, "provider_$1");
    const sdkPath = options.sdkPath ?? relativeCommand(output, join(repoRoot, "provider-sdk-kmp", "rust"));
    const cargo = `[package]
name = "${crate}"
version = "0.1.0"
edition = "2021"
rust-version = "1.75"

[lib]
crate-type = ["cdylib", "rlib"]

[dependencies]
cpplayer-provider-sdk = { path = "${sdkPath}" }

[profile.release]
lto = "thin"
`;
    mkdirSync(join(output, "src"), { recursive: true });
    writeFileSync(join(output, "Cargo.toml"), cargo, "utf8");
    writeFileSync(join(output, "src", "lib.rs"), rustSource(selected), "utf8");
    writeFileSync(join(output, ".gitignore"), "/target\n", "utf8");
    writeFileSync(join(output, "README.md"), rustReadme(options, selected, packScript, sdkPath), "utf8");
  }

  console.log(`Generated ${options.kind} provider at ${output}`);
  console.log(`  id         ${options.id}`);
  console.log(`  operations ${selected.map(([name]) => name).join(", ")}`);
  console.log(`  pack       node ${packScript} . -o dist/${options.id}-0.1.0.zip`);
}

try {
  main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
