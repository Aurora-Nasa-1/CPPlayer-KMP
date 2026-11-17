import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const script = join(root, "scripts", "new-provider.mjs");

function run(args, cwd = root) {
  return spawnSync(process.execPath, [script, ...args], {
    cwd,
    encoding: "utf8",
  });
}

function tempDir() {
  return mkdtempSync(join(tmpdir(), "cpplayer-scaffold-"));
}

test("generates only the selected JS operations", () => {
  const output = tempDir();
  try {
    const result = run([
      "--kind", "js",
      "--with", "search,resolve",
      "--id", "test-provider",
      "--name", "Test Provider",
      "--out", output,
    ]);
    assert.equal(result.status, 0, result.stderr);
    const manifest = JSON.parse(readFileSync(join(output, "manifest.json"), "utf8"));
    assert.deepEqual(manifest.capabilities, ["tracks.search", "tracks.resolve"]);
    const source = readFileSync(join(output, "index.js"), "utf8");
    assert.match(source, /async search\(/);
    assert.match(source, /async resolve\(/);
    assert.doesNotMatch(source, /async get\(/);
    assert.doesNotMatch(source, /tracks\.get/);
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
});

test("generates a Rust provider with only the selected capabilities", () => {
  const output = tempDir();
  try {
    const result = run([
      "--kind", "rust",
      "--with", "search,resolve",
      "--id", "test-rust",
      "--name", "Test Rust",
      "--out", output,
    ]);
    assert.equal(result.status, 0, result.stderr);
    const cargo = readFileSync(join(output, "Cargo.toml"), "utf8");
    assert.match(cargo, /name = "test_rust"/);
    assert.match(cargo, /cpplayer-provider-sdk/);
    const source = readFileSync(join(output, "src", "lib.rs"), "utf8");
    assert.match(source, /Capability::SEARCH/);
    assert.match(source, /Capability::RESOLVE/);
    assert.doesNotMatch(source, /Capability::GET/);
    assert.match(source, /fn create\(_context: &ProviderContext\) -> Box<dyn MusicProvider>/);
    assert.match(source, /export_provider!\(create\)/);
    assert.match(readFileSync(join(output, ".gitignore"), "utf8"), /target/);
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
});

test("rejects unknown operations and unsafe IDs", () => {
  const unknown = run(["--kind", "js", "--with", "does-not-exist", "--out", join(tempDir(), "x")]);
  assert.notEqual(unknown.status, 0);
  assert.match(unknown.stderr, /Unknown operation or capability/);

  const unsafe = run(["--kind", "js", "--id", "../escape", "--out", join(tempDir(), "y")]);
  assert.notEqual(unsafe.status, 0);
  assert.match(unsafe.stderr, /--id/);
});
