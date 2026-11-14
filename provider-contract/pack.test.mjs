import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";
import test from "node:test";
import {
  MAX_SCRIPT_BYTES,
  PackageError,
  manifestIssues,
  packDirectory,
} from "./pack.mjs";

function tempDir() {
  return mkdtempSync(join(tmpdir(), "cpplayer-pack-"));
}

function writeProvider(root, manifest = {}, entry = "globalThis.provider = { capabilities: [] };") {
  mkdirSync(root, { recursive: true });
  writeFileSync(
    join(root, "manifest.json"),
    JSON.stringify({
      id: "test-provider",
      name: "Test Provider",
      version: "1.0.0",
      type: "js",
      contract: "kmp-sdk-v1",
      entryPoint: "index.js",
      ...manifest,
    }, null, 2),
    "utf8",
  );
  writeFileSync(join(root, "index.js"), entry, "utf8");
}

function readZipEntries(zip) {
  const entries = [];
  let offset = 0;
  while (zip.readUInt32LE(offset) === 0x04034b50) {
    const method = zip.readUInt16LE(offset + 8);
    const compressedSize = zip.readUInt32LE(offset + 18);
    const uncompressedSize = zip.readUInt32LE(offset + 22);
    const nameLength = zip.readUInt16LE(offset + 26);
    const extraLength = zip.readUInt16LE(offset + 28);
    const name = zip.subarray(offset + 30, offset + 30 + nameLength).toString("utf8");
    const dataStart = offset + 30 + nameLength + extraLength;
    const payload = zip.subarray(dataStart, dataStart + compressedSize);
    const data = method === 8 ? inflateRawSync(payload) : payload;
    assert.equal(data.length, uncompressedSize);
    entries.push({ name, data });
    offset = dataStart + compressedSize;
  }
  return entries;
}

test("packs a valid provider deterministically", () => {
  const root = tempDir();
  const output = tempDir();
  try {
    writeProvider(root);
    const first = packDirectory(root, { output: join(output, "one.zip") });
    const second = packDirectory(root, { output: join(output, "two.zip") });
    assert.equal(first.sha256, second.sha256);
    assert.equal(first.bytes, second.bytes);
    assert.equal(first.files, 2);
    assert.equal(first.manifest.id, "test-provider");

    const entries = readZipEntries(readFileSync(first.path));
    assert.deepEqual(entries.map((entry) => entry.name), ["index.js", "manifest.json"]);
    assert.match(entries[0].data.toString("utf8"), /globalThis\.provider/);
  } finally {
    rmSync(root, { recursive: true, force: true });
    rmSync(output, { recursive: true, force: true });
  }
});

test("manifest validation mirrors install-time identity and origin rules", () => {
  const valid = {
    id: "test-provider",
    name: "Test",
    version: "1.2.3",
    type: "js",
    contract: "kmp-sdk-v1",
    entryPoint: "index.js",
    sdkOrigins: ["https://api.example.com", "http://127.0.0.1:48482", "http://[::1]:8080"],
  };
  assert.deepEqual(manifestIssues(valid), []);

  for (const id of ["../escape", "local", "UPPER", "a..b", "a/b", ""]) {
    assert.ok(manifestIssues({ ...valid, id }).some((error) => error.includes("provider ID")), id);
  }
  for (const version of ["1.0", "01.0.0", "v1.0.0", "1.0.0-"]) {
    assert.ok(manifestIssues({ ...valid, version }).some((error) => error.includes("SemVer")), version);
  }
  for (const entryPoint of ["../index.js", "dir\\index.js", "/index.js", "C:\\index.js", "a//b"]) {
    assert.ok(manifestIssues({ ...valid, entryPoint }).some((error) => error.includes("entryPoint")), entryPoint);
  }
  for (const origin of ["https://example.com/", "https://example.com/path", "http://example.com", "ftp://example.com"]) {
    assert.ok(manifestIssues({ ...valid, sdkOrigins: [origin] }).length > 0, origin);
  }
  assert.ok(manifestIssues({ ...valid, contract: "standard" }).some((error) => error.includes("contract")));
  assert.ok(manifestIssues({ ...valid, minHostApiVersion: 4 }).some((error) => error.includes("host API")));
});

test("rejects dependency and build directories", () => {
  const root = tempDir();
  try {
    writeProvider(root);
    mkdirSync(join(root, "node_modules", "pkg"), { recursive: true });
    writeFileSync(join(root, "node_modules", "pkg", "index.js"), "module.exports = 1;", "utf8");
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), /node_modules/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("rejects invalid script encoding and oversized scripts", () => {
  const root = tempDir();
  try {
    writeProvider(root);
    writeFileSync(join(root, "index.js"), Buffer.from([0xe4, 0xb8]));
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), /strict UTF-8/);

    writeFileSync(join(root, "index.js"), Buffer.alloc(MAX_SCRIPT_BYTES + 1, 0x61));
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), /limit is/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("native packages require real library magic", () => {
  const root = tempDir();
  try {
    writeProvider(root, {
      type: "native",
      entryPoint: "provider.dll",
      supportedAbis: ["x86_64"],
    });
    writeFileSync(join(root, "provider.dll"), "not a library", "utf8");
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), /recognizable DLL/);

    writeFileSync(join(root, "provider.dll"), Buffer.from([0x4d, 0x5a, 0x90, 0x00]));
    const result = packDirectory(root, { output: join(root, "out.zip") });
    assert.equal(result.manifest.type, "native");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("native packages ignore Cargo target/ but JS packages reject it", () => {
  const root = tempDir();
  try {
    writeProvider(root, {
      type: "native",
      entryPoint: "provider.dll",
      supportedAbis: ["x86_64"],
    });
    writeFileSync(join(root, "provider.dll"), Buffer.from([0x4d, 0x5a, 0x90, 0x00]));
    mkdirSync(join(root, "target", "release"), { recursive: true });
    writeFileSync(join(root, "target", "release", "provider.dll"), Buffer.from([0x4d, 0x5a]));
    const result = packDirectory(root, { output: join(root, "out.zip") });
    assert.equal(result.files, 3);

    writeProvider(root, { type: "js", entryPoint: "index.js" });
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), /target/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("missing entry points fail before a ZIP is written", () => {
  const root = tempDir();
  try {
    writeProvider(root, { entryPoint: "missing.js" });
    assert.throws(() => packDirectory(root, { output: join(root, "out.zip") }), PackageError);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
