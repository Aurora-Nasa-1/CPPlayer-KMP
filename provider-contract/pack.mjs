/**
 * Zero-dependency packer for kmp-sdk-v1 provider packages.
 *
 * Usage:
 *   node provider-contract/pack.mjs <provider-dir> [-o out.zip] [--json]
 *
 * The packer mirrors the host's install-time checks before writing a ZIP:
 * manifest contract/identity/entryPoint, entry size and encoding, native
 * library magic, and an explicit denylist for dependency/build directories.
 */
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { deflateRawSync } from "node:zlib";

export const CONTRACT = "kmp-sdk-v1";
export const MAX_SCRIPT_BYTES = 1024 * 1024;
export const MAX_NATIVE_BYTES = 64 * 1024 * 1024;
export const MAX_FILES = 512;

const ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,127}$/;
const SEMVER_PATTERN =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const CLASS_PATTERN = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/;
const SETTING_TYPES = new Set(["string", "password", "switch", "select"]);
const DENIED_DIRECTORIES = new Set(["node_modules", ".git", "target"]);
const IGNORED_FILES = new Set([".DS_Store", "Thumbs.db"]);

export class PackageError extends Error {
  constructor(message) {
    super(message);
    this.name = "PackageError";
  }
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function issue(errors, condition, message) {
  if (!condition) errors.push(message);
}

function isSafeRelativePath(value) {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    !value.startsWith("/") &&
    !value.includes("\\") &&
    !value.includes(":") &&
    value.split("/").every((segment) => segment !== "" && segment !== "." && segment !== "..")
  );
}

function validateOrigin(value) {
  if (typeof value !== "string" || value.length === 0 || value.trim() !== value) {
    return "sdkOrigins entries must be non-empty strings without surrounding whitespace";
  }
  const schemeEnd = value.indexOf("://");
  if (schemeEnd <= 0) return `Invalid SDK origin: ${value}`;
  const scheme = value.slice(0, schemeEnd).toLowerCase();
  if (scheme !== "http" && scheme !== "https") {
    return `SDK origin must use http or https: ${value}`;
  }
  const authority = value.slice(schemeEnd + 3);
  if (authority.length === 0) return `Invalid SDK origin: ${value}`;
  if (/[/?#]/.test(authority)) {
    return `SDK origin must not contain a path, query, or fragment: ${value}`;
  }
  if (authority.includes("@")) {
    return `SDK origin must not contain user info: ${value}`;
  }
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    return `Invalid SDK origin: ${value}`;
  }
  if (!parsed.hostname) return `Invalid SDK origin: ${value}`;
  const host = parsed.hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (scheme === "http" && !["localhost", "127.0.0.1", "::1"].includes(host)) {
    return `Plain HTTP is only allowed on loopback: ${value}`;
  }
  return null;
}

/**
 * Validate only the manifest fields the host uses before executing provider code.
 * File-system checks (entryPoint existence, size, encoding) belong to packDirectory.
 */
export function manifestIssues(manifest) {
  const errors = [];
  if (!isObject(manifest)) return ["manifest.json must contain a JSON object"];

  issue(errors, typeof manifest.id === "string" && ID_PATTERN.test(manifest.id) &&
    !manifest.id.includes("..") && manifest.id !== "local", "Invalid provider ID");
  issue(errors, typeof manifest.name === "string" && manifest.name.trim().length > 0,
    "manifest.name must be a non-empty string");
  issue(errors, typeof manifest.version === "string" && SEMVER_PATTERN.test(manifest.version),
    "manifest.version must be strict SemVer (for example 1.0.0)");
  issue(errors, typeof manifest.contract === "string" && manifest.contract === CONTRACT,
    `manifest.contract must be exactly ${CONTRACT}`);
  issue(errors, ["js", "script", "jni", "native"].includes(manifest.type),
    `Unsupported SDK provider type: ${String(manifest.type)}`);
  issue(errors, isSafeRelativePath(manifest.entryPoint),
    "manifest.entryPoint must be a relative path inside the package");

  const isNative = manifest.type === "jni" || manifest.type === "native";
  if (manifest.nativeFactoryClass !== undefined && manifest.nativeFactoryClass !== null) {
    issue(errors, typeof manifest.nativeFactoryClass === "string" &&
      CLASS_PATTERN.test(manifest.nativeFactoryClass),
      "manifest.nativeFactoryClass must be a fully qualified class name");
  }

  for (const key of ["apiVersion", "minHostApiVersion"]) {
    const value = manifest[key];
    if (value === undefined || value === null) continue;
    issue(errors, Number.isInteger(value) && value >= 1, `manifest.${key} must be a positive integer`);
  }
  if (Number.isInteger(manifest.minHostApiVersion) && manifest.minHostApiVersion > 3) {
    errors.push("manifest.minHostApiVersion must not exceed the current host API version 3");
  }

  if (manifest.sdkOrigins !== undefined) {
    issue(errors, Array.isArray(manifest.sdkOrigins), "manifest.sdkOrigins must be an array");
    if (Array.isArray(manifest.sdkOrigins)) {
      for (const origin of manifest.sdkOrigins) {
        const error = validateOrigin(origin);
        if (error) errors.push(error);
      }
    }
  }

  for (const key of ["capabilities", "supportedAbis"]) {
    const value = manifest[key];
    if (value === undefined) continue;
    issue(errors, Array.isArray(value) && value.every((item) => typeof item === "string" && item.trim().length > 0),
      `manifest.${key} must be an array of non-empty strings`);
  }

  if (manifest.settingsSchema !== undefined) {
    issue(errors, Array.isArray(manifest.settingsSchema), "manifest.settingsSchema must be an array");
    if (Array.isArray(manifest.settingsSchema)) {
      for (const [index, item] of manifest.settingsSchema.entries()) {
        issue(errors, isObject(item), `manifest.settingsSchema[${index}] must be an object`);
        if (!isObject(item)) continue;
        issue(errors, typeof item.key === "string" && item.key.trim().length > 0,
          `manifest.settingsSchema[${index}].key must be a non-empty string`);
        issue(errors, typeof item.label === "string" && item.label.trim().length > 0,
          `manifest.settingsSchema[${index}].label must be a non-empty string`);
        issue(errors, typeof item.type === "string" && SETTING_TYPES.has(item.type),
          `manifest.settingsSchema[${index}].type must be one of ${[...SETTING_TYPES].join(", ")}`);
        if (item.type === "select") {
          issue(errors, Array.isArray(item.options) && item.options.length > 0,
            `manifest.settingsSchema[${index}].options is required for select`);
        }
      }
    }
  }

  if (manifest.qualityOptions !== undefined) {
    issue(errors, Array.isArray(manifest.qualityOptions), "manifest.qualityOptions must be an array");
    if (Array.isArray(manifest.qualityOptions)) {
      for (const [index, item] of manifest.qualityOptions.entries()) {
        issue(errors, isObject(item), `manifest.qualityOptions[${index}] must be an object`);
        if (!isObject(item)) continue;
        for (const key of ["key", "label", "targetQuality"]) {
          issue(errors, typeof item[key] === "string" && item[key].trim().length > 0,
            `manifest.qualityOptions[${index}].${key} must be a non-empty string`);
        }
      }
    }
  }

  return errors;
}

export function validateManifest(manifest) {
  const errors = manifestIssues(manifest);
  if (errors.length > 0) throw new PackageError(errors.join("\n"));
  return manifest;
}

function decodeUtf8(buffer, label) {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(buffer);
  } catch {
    throw new PackageError(`${label} must be strict UTF-8`);
  }
}

function readManifest(root) {
  const path = join(root, "manifest.json");
  if (!existsSync(path) || !statSync(path).isFile()) {
    throw new PackageError("Package root must contain manifest.json");
  }
  let text = decodeUtf8(readFileSync(path), "manifest.json");
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  let manifest;
  try {
    manifest = JSON.parse(text);
  } catch (error) {
    throw new PackageError(`manifest.json is not valid JSON: ${error.message}`);
  }
  return validateManifest(manifest);
}

function collectFiles(root, outputPath, { skipTarget = false } = {}) {
  const files = [];
  const denied = [];
  const walk = (directory) => {
    const entries = readdirSync(directory, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      const absolute = join(directory, entry.name);
      if (outputPath && resolve(absolute) === outputPath) continue;
      if (entry.isSymbolicLink()) {
        throw new PackageError(`Symbolic links are not allowed in provider packages: ${relative(root, absolute)}`);
      }
      // Cargo's target/ is build output, not package content. Only native
      // packages may carry it; JS packages still reject it as a mistake.
      if (skipTarget && entry.name === "target") continue;
      if (DENIED_DIRECTORIES.has(entry.name)) {
        denied.push(relative(root, absolute).split(sep).join("/"));
        continue;
      }
      if (entry.isDirectory()) {
        walk(absolute);
        continue;
      }
      if (!entry.isFile()) continue;
      if (IGNORED_FILES.has(entry.name)) continue;
      const name = relative(root, absolute).split(sep).join("/");
      if (!isSafeRelativePath(name)) {
        throw new PackageError(`Unsafe package path: ${name}`);
      }
      files.push({ name, absolute, bytes: statSync(absolute).size });
    }
  };
  walk(root);
  if (denied.length > 0) {
    throw new PackageError(
      `Remove dependency/build directories before packing: ${denied.join(", ")}`,
    );
  }
  if (files.length > MAX_FILES) {
    throw new PackageError(`Provider package has ${files.length} files; the limit is ${MAX_FILES}`);
  }
  return files.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

function startsWithBytes(buffer, bytes) {
  return bytes.every((byte, index) => buffer[index] === byte);
}

function nativeKind(buffer) {
  if (startsWithBytes(buffer, [0x4d, 0x5a])) return "PE";
  if (startsWithBytes(buffer, [0x7f, 0x45, 0x4c, 0x46])) return "ELF";
  if (
    startsWithBytes(buffer, [0xfe, 0xed, 0xfa, 0xce]) ||
    startsWithBytes(buffer, [0xce, 0xfa, 0xed, 0xfe]) ||
    startsWithBytes(buffer, [0xfe, 0xed, 0xfa, 0xcf]) ||
    startsWithBytes(buffer, [0xcf, 0xfa, 0xed, 0xfe]) ||
    startsWithBytes(buffer, [0xca, 0xfe, 0xba, 0xbe])
  ) return "Mach-O";
  if (startsWithBytes(buffer, [0x50, 0x4b, 0x03, 0x04])) return "ZIP/JAR";
  return null;
}

function validateEntryPoint(root, manifest) {
  const entryPath = join(root, ...manifest.entryPoint.split("/"));
  if (!existsSync(entryPath) || !statSync(entryPath).isFile()) {
    throw new PackageError(`manifest.entryPoint does not exist: ${manifest.entryPoint}`);
  }
  const size = statSync(entryPath).size;
  const isNative = manifest.type === "jni" || manifest.type === "native";
  const limit = isNative ? MAX_NATIVE_BYTES : MAX_SCRIPT_BYTES;
  if (size > limit) {
    throw new PackageError(
      `${manifest.entryPoint} is ${size} bytes; the limit is ${limit} bytes`,
    );
  }
  const buffer = readFileSync(entryPath);
  if (!isNative) {
    decodeUtf8(buffer, manifest.entryPoint);
    return;
  }
  const kind = nativeKind(buffer);
  const expectsJar = Boolean(manifest.nativeFactoryClass) || manifest.entryPoint.toLowerCase().endsWith(".jar");
  if (expectsJar) {
    if (kind !== "ZIP/JAR") {
      throw new PackageError(`${manifest.entryPoint} must be a JAR/ZIP when nativeFactoryClass is used`);
    }
    return;
  }
  if (!["PE", "ELF", "Mach-O"].includes(kind)) {
    throw new PackageError(
      `${manifest.entryPoint} is not a recognizable DLL/SO/dylib (expected PE, ELF, or Mach-O magic)`,
    );
  }
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index++) {
    let value = index;
    for (let bit = 0; bit < 8; bit++) {
      value = (value & 1) !== 0 ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    }
    table[index] = value >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function uint16(value) {
  const buffer = Buffer.alloc(2);
  buffer.writeUInt16LE(value, 0);
  return buffer;
}

function uint32(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32LE(value >>> 0, 0);
  return buffer;
}

function buildZip(entries) {
  const locals = [];
  const central = [];
  let offset = 0;
  // 1980-01-01 00:00:00 is the DOS epoch. A fixed timestamp makes output reproducible.
  const dosTime = 0;
  const dosDate = 0x0021;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, "utf8");
    const crc = crc32(entry.data);
    const compressed = deflateRawSync(entry.data, { level: 9 });
    const useDeflate = compressed.length < entry.data.length;
    const payload = useDeflate ? compressed : entry.data;
    const method = useDeflate ? 8 : 0;
    const local = Buffer.concat([
      uint32(0x04034b50),
      uint16(20),
      uint16(0x0800),
      uint16(method),
      uint16(dosTime),
      uint16(dosDate),
      uint32(crc),
      uint32(payload.length),
      uint32(entry.data.length),
      uint16(name.length),
      uint16(0),
      name,
      payload,
    ]);
    const centralHeader = Buffer.concat([
      uint32(0x02014b50),
      uint16(20),
      uint16(20),
      uint16(0x0800),
      uint16(method),
      uint16(dosTime),
      uint16(dosDate),
      uint32(crc),
      uint32(payload.length),
      uint32(entry.data.length),
      uint16(name.length),
      uint16(0),
      uint16(0),
      uint16(0),
      uint16(0),
      uint32(0),
      uint32(offset),
      name,
    ]);
    locals.push(local);
    central.push(centralHeader);
    offset += local.length;
  }
  const centralBuffer = Buffer.concat(central);
  const end = Buffer.concat([
    uint32(0x06054b50),
    uint16(0),
    uint16(0),
    uint16(entries.length),
    uint16(entries.length),
    uint32(centralBuffer.length),
    uint32(offset),
    uint16(0),
  ]);
  return Buffer.concat([...locals, centralBuffer, end]);
}

/**
 * Validate a provider directory and write a deterministic ZIP.
 * @returns {{path: string, bytes: number, sha256: string, files: number, manifest: object}}
 */
export function packDirectory(inputDirectory, options = {}) {
  const root = resolve(inputDirectory);
  if (!existsSync(root) || !statSync(root).isDirectory()) {
    throw new PackageError(`Provider directory does not exist: ${root}`);
  }
  const manifest = readManifest(root);
  validateEntryPoint(root, manifest);
  const outputPath = resolve(
    options.output ?? join(dirname(root), "dist", `${manifest.id}-${manifest.version}.zip`),
  );
  const files = collectFiles(root, outputPath, {
    skipTarget: manifest.type === "native" || manifest.type === "jni",
  });
  if (!files.some((file) => file.name === "manifest.json")) {
    throw new PackageError("Package root must contain manifest.json");
  }
  const entries = files.map((file) => ({ name: file.name, data: readFileSync(file.absolute) }));
  const zip = buildZip(entries);
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, zip);
  const bytes = statSync(outputPath).size;
  const sha256 = createHash("sha256").update(zip).digest("hex");
  return { path: outputPath, bytes, sha256, files: entries.length, manifest };
}

function parseArgs(argv) {
  let input = null;
  let output = null;
  let json = false;
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === "--json") {
      json = true;
      continue;
    }
    if (arg === "-o" || arg === "--output") {
      const value = argv[++index];
      if (!value) throw new PackageError(`${arg} requires a path`);
      output = value;
      continue;
    }
    if (arg === "-h" || arg === "--help") {
      return { help: true };
    }
    if (arg.startsWith("-")) throw new PackageError(`Unknown option: ${arg}`);
    if (input !== null) throw new PackageError(`Unexpected argument: ${arg}`);
    input = arg;
  }
  if (input === null) throw new PackageError("Usage: node provider-contract/pack.mjs <provider-dir> [-o out.zip] [--json]");
  return { input, output, json };
}

function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
    if (options.help) {
      console.log("Usage: node provider-contract/pack.mjs <provider-dir> [-o out.zip] [--json]");
      return;
    }
    const result = packDirectory(options.input, { output: options.output });
    if (options.json) {
      console.log(JSON.stringify({
        path: result.path,
        bytes: result.bytes,
        sha256: result.sha256,
        files: result.files,
        id: result.manifest.id,
        version: result.manifest.version,
        type: result.manifest.type,
      }, null, 2));
    } else {
      console.log(`Packed ${result.manifest.id} ${result.manifest.version} (${result.manifest.type})`);
      console.log(`  path   ${result.path}`);
      console.log(`  bytes  ${result.bytes}`);
      console.log(`  sha256 ${result.sha256}`);
      console.log(`  files  ${result.files}`);
    }
  } catch (error) {
    console.error(error instanceof PackageError ? error.message : error.stack);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main();
}
