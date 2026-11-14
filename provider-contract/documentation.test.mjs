import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import schema from "./schema.mjs";
import { operations, records, fields, fieldOverrides } from "./documentation.mjs";

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const kotlin = [
  "provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/MusicProvider.kt",
  "provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/CatalogProvider.kt",
  "provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/ExtendedOperations.generated.kt",
].map(read).join("\n");
const ts = read("provider-sdk-kmp/js/types.generated.ts");
const operationTable = read("docs/dev/providers/SDK_OPERATIONS.md");
const typeTable = read("docs/dev/providers/SDK_TYPES.md");

test("every business method has the same signature and semantic documentation in Kotlin TS and reference", () => {
  const business = Object.entries(schema.operations).filter(([, op]) => op.capability);
  assert.equal(Object.keys(operations).length, business.length);
  for (const [name, op] of business) {
    const declaration = `suspend fun ${name}(request: ${op.request}): ${op.response}`;
    assert.equal(kotlin.split(declaration).length - 1, 1, `Unique Kotlin declaration: ${name}`);
    assert.ok(kotlin.includes(`/** ${operations[name]} Capability: ${op.capability}. Tag: ${op.tag}.`), name);
    assert.ok(ts.includes(`${name}(request: ${op.request}, context: Context): Promise<${op.response}>;`), name);
    assert.ok(ts.includes(`/** ${operations[name]} Capability: ${op.capability}. Tag: ${op.tag}.`), name);
    assert.ok(operationTable.includes(`| ${op.tag} | \`${name}\` | \`${op.capability}\``), name);
    assert.ok(operationTable.includes(operations[name]), name);
    assert.ok(operationTable.includes(`(SDK_TYPES.md#${op.request.toLowerCase()})`), name);
  }
});

test("every record and field has generated editor and reference semantics", () => {
  const models = read("provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/Models.generated.kt");
  assert.deepEqual(Object.keys(records), Object.keys(schema.records));
  for (const [name, record] of Object.entries(schema.records)) {
    assert.ok(typeTable.includes(`## ${name}\n`), name);
    assert.ok(models.includes(`/** ${records[name]} */\ndata class ${name}(`), name);
    assert.ok(ts.includes(`/** ${records[name]} */\nexport interface ${name} {`), name);
    for (const field of Object.keys(record)) {
      const description = fieldOverrides[`${name}.${field}`] ?? fields[field];
      assert.ok(description, `${name}.${field}`);
      assert.ok(models.includes(`/** ${description} */\n    val ${field}:`), `${name}.${field}`);
      assert.ok(ts.includes(`/** ${description} */\n  readonly ${field}`), `${name}.${field}`);
      assert.ok(typeTable.includes(description), `${name}.${field}`);
    }
  }
});

test("documented queue auth and selector literals match host validation and SDK declarations", () => {
  const validation = read("core/src/commonMain/kotlin/cp/player/core/provider/typed/ExtendedOperationValidation.kt");
  const provider = read("provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/MusicProvider.kt");
  for (const value of ["REPLACE", "ADD", "DELETE"]) {
    assert.ok(validation.includes(`"${value}"`));
    assert.ok(fieldOverrides["ListenQueueRequest.action"].includes(value));
  }
  for (const value of ["PLAY", "PAUSE", "PROGRESS", "GOTO"]) {
    assert.ok(validation.includes(`"${value}"`));
    assert.ok(fieldOverrides["ListenCommand.action"].includes(value));
  }
  const workflows = read("docs/dev/providers/WORKFLOWS.md");
  const authActions = [...provider.matchAll(/const val \w+ = "((?:qr|login|captcha|webview|verify)\.[^"]+|logout)"/g)];
  assert.equal(authActions.length, 13);
  for (const [, action] of authActions) assert.ok(workflows.includes(`\`${action}\``), action);
});

test("documentation links resolve to local files and type anchors", () => {
  for (const path of [
    "README.md", "CONTRIBUTING.md", "AGENTS.md",
    "docs/index.md", "docs/USER_GUIDE.md",
    "docs/dev/ARCHITECTURE.md", "docs/dev/BUILD.md", "docs/dev/RELEASE.md",
    "docs/dev/INTEGRATION_API.md", "docs/dev/FLICK_ENGINE.md",
    "docs/dev/FONTS.md", "docs/dev/I18N.md",
    "docs/dev/providers/index.md", "docs/dev/providers/QUICKSTART.md",
    "docs/dev/providers/GUIDE.md", "docs/dev/providers/MANIFEST.md",
    "docs/dev/providers/NATIVE.md", "docs/dev/providers/JS.md",
    "docs/dev/providers/WORKFLOWS.md", "docs/dev/providers/SDK_OPERATIONS.md",
    "docs/dev/lyrics/LYRICS_PLUGIN.md", "docs/history/index.md",
    "provider-contract/README.md", "provider-sdk-kmp/README.md",
  ]) {
    const base = new URL(`../${path}`, import.meta.url);
    for (const [, target] of read(path).matchAll(/\[[^\]]*\]\(([^)\s]+)\)/g)) {
      if (/^(?:[a-z]+:|#)/i.test(target)) continue;
      const url = new URL(target, base);
      assert.ok(existsSync(url), `${path}: ${target}`);
      if (url.hash && url.pathname.endsWith("/SDK_TYPES.md")) {
        const headings = [...typeTable.matchAll(/^## (\w+)$/gm)].map(([, h]) => h.toLowerCase());
        assert.ok(headings.includes(url.hash.slice(1)), `${path}: ${target}`);
      }
    }
  }
});
