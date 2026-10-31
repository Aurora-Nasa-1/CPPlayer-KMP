import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

const workflow = readFileSync(new URL("../../.github/workflows/sentry-issue-sync.yml", import.meta.url), "utf8")
    .replace(/\r\n/g, "\n");
assert.match(workflow, /GH_REPO: \$\{\{ github\.repository \}\}/);
const script = workflow.split("        run: |\n")[1].replace(/^          /gm, "");
const bash = process.env.TEST_BASH ?? (process.platform === "win32" ? "C:/Program Files/Git/bin/bash.exe" : "bash");
const jq = process.env.TEST_JQ ?? "jq";
const sentryTitle = "RemoteServiceException$ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{9be9971 u0 cp.player/.core.lyricpush.XiaomiSuperIslandLyricService c:cp.player}";
const issue = { id: "123", shortId: "CPPLAYER-KMP-1", title: sentryTitle };

function runSync(issues, overrides = {}) {
    const cwd = mkdtempSync(path.join(tmpdir(), "sentry-sync-"));
    const log = path.join(cwd, "gh.jsonl");
    const mock = path.join(cwd, "gh-mock.cjs");
    writeFileSync(mock, `
const fs = require("node:fs");
const args = process.argv.slice(2);
if (process.env.GH_REPO !== "fixture/player") process.exit(90);
fs.appendFileSync(process.env.TEST_GH_LOG, JSON.stringify(args) + "\\n");
if (args[0] === "issue" && args[1] === "list") {
    if (process.env.TEST_LIST_FAIL) process.exit(1);
    console.log(process.env.TEST_EXISTING_TITLES || "");
} else if (args[0] === "label" && process.env.TEST_LABEL_FAIL) {
    process.exit(1);
}
`);
    try {
        const result = spawnSync(bash, ["--noprofile", "--norc", "-s"], {
            cwd,
            encoding: "utf8",
            timeout: 30_000,
            env: {
                ...process.env,
                GH_TOKEN: "fixture",
                GH_REPO: "fixture/player",
                SENTRY_AUTH_TOKEN: "fixture",
                SENTRY_HOST: "sentry.invalid",
                SENTRY_ORG: "fixture",
                SENTRY_PROJECT: "fixture",
                TEST_NODE: process.execPath,
                TEST_JQ: jq,
                TEST_GH_MOCK: mock,
                TEST_GH_LOG: log,
                TEST_SENTRY_JSON: JSON.stringify(issues),
                ...overrides,
            },
            input: `
curl() { if [ -n "\${TEST_FETCH_FAIL:-}" ]; then return 22; fi; printf '%s' "$TEST_SENTRY_JSON"; }
gh() { "$TEST_NODE" "$TEST_GH_MOCK" "$@"; }
jq() { command "$TEST_JQ" "$@"; }
${script}`,
        });
        if (result.error) throw result.error;
        let calls = [];
        try {
            calls = readFileSync(log, "utf8").trim().split("\n").map(JSON.parse);
        } catch (error) {
            if (error.code !== "ENOENT") throw error;
        }
        return { ...result, calls };
    } finally {
        rmSync(cwd, { recursive: true, force: true });
    }
}

test("no token and no unresolved issues both skip GitHub calls", () => {
    for (const result of [runSync([issue], { SENTRY_AUTH_TOKEN: "" }), runSync([])]) {
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(result.calls, []);
    }
});

test("sync works outside a git checkout and retains the complete long exception", () => {
    const result = runSync([issue]);
    assert.equal(result.status, 0, result.stderr);
    const create = result.calls.find(([command, action]) => command === "issue" && action === "create");
    assert.ok(create);
    const title = create[create.indexOf("--title") + 1];
    const body = create[create.indexOf("--body") + 1];
    assert.equal(Array.from(title).length, 256);
    assert.ok(title.startsWith("[Sentry CPPLAYER-KMP-1] "));
    assert.ok(title.endsWith("..."));
    assert.ok(body.includes(sentryTitle));
});

test("existing identifiers are deduplicated even if the exception text changed", () => {
    const result = runSync([issue], { TEST_EXISTING_TITLES: "[Sentry CPPLAYER-KMP-1] previous title" });
    assert.equal(result.status, 0, result.stderr);
    assert.ok(!result.calls.some(([command, action]) => command === "issue" && action === "create"));
});

test("a missing shortId falls back to the numeric Sentry identifier", () => {
    const result = runSync([{ ...issue, shortId: null, title: "Crash" }]);
    assert.equal(result.status, 0, result.stderr);
    const create = result.calls.find(([command, action]) => command === "issue" && action === "create");
    assert.equal(create[create.indexOf("--title") + 1], "[Sentry 123] Crash");
});

test("deduplication and label failures stop before issue creation", () => {
    for (const overrides of [{ TEST_LIST_FAIL: "1" }, { TEST_LABEL_FAIL: "1" }]) {
        const result = runSync([issue], overrides);
        assert.notEqual(result.status, 0);
        assert.ok(!result.calls.some(([command, action]) => command === "issue" && action === "create"));
    }
});

test("Sentry fetch failures are not reported as an empty success", () => {
    const result = runSync([issue], { TEST_FETCH_FAIL: "1" });
    assert.notEqual(result.status, 0);
    assert.deepEqual(result.calls, []);
});
