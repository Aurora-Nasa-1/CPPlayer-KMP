import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import http from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
    resolveChatCompletionsUrl,
    resolveOpenAiConfig,
} from "../generate_ai_changelog.mjs";

const script = fileURLToPath(new URL("../generate_ai_changelog.mjs", import.meta.url));

test("base URL 归一化兼容默认值、尾斜杠与完整端点", () => {
    assert.equal(
        resolveChatCompletionsUrl(""),
        "https://api.openai.com/v1/chat/completions",
    );
    assert.equal(
        resolveChatCompletionsUrl(" https://api.example.com/v1/ "),
        "https://api.example.com/v1/chat/completions",
    );
    assert.equal(
        resolveChatCompletionsUrl("https://api.example.com/v1/chat/completions/"),
        "https://api.example.com/v1/chat/completions",
    );
});

test("OpenAI 配置优先使用自定义 base URL 与模型", () => {
    assert.deepEqual(resolveOpenAiConfig({}), {
        apiKey: "",
        baseUrl: "https://api.openai.com/v1",
        model: "gpt-4o-mini",
    });
    assert.deepEqual(
        resolveOpenAiConfig({
            OPENAI_API_KEY: " key ",
            OPENAI_BASE_URL: " https://gateway.example.com/v1 ",
            OPENAI_MODEL: " custom-model ",
        }),
        {
            apiKey: "key",
            baseUrl: "https://gateway.example.com/v1",
            model: "custom-model",
        },
    );
});

function runScript(args, env) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.execPath, [script, ...args], {
            env: { ...process.env, ...env },
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (chunk) => (stdout += chunk));
        child.stderr.on("data", (chunk) => (stderr += chunk));
        child.on("error", reject);
        child.on("close", (code) => resolve({ code, stdout, stderr }));
    });
}

test("自定义 base URL 实际用于 chat completions 请求", async () => {
    let request;
    const server = http.createServer((req, res) => {
        let body = "";
        req.on("data", (chunk) => (body += chunk));
        req.on("end", () => {
            request = {
                url: req.url,
                authorization: req.headers.authorization,
                body: JSON.parse(body),
            };
            res.writeHead(200, { "content-type": "application/json" });
            res.end(JSON.stringify({
                choices: [{ message: { content: "### 功能新增\n- 测试条目" } }],
            }));
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));

    const dir = mkdtempSync(path.join(tmpdir(), "ai-changelog-"));
    const output = path.join(dir, "notes.md");
    try {
        const { port } = server.address();
        const result = await runScript(["", "HEAD", output], {
            COMMITS: "- feat: 自定义端点",
            OPENAI_API_KEY: "fixture-key",
            OPENAI_BASE_URL: `http://127.0.0.1:${port}/v1/`,
            OPENAI_MODEL: "fixture-model",
        });
        assert.equal(result.code, 0, result.stderr);
        assert.equal(request.url, "/v1/chat/completions");
        assert.equal(request.authorization, "Bearer fixture-key");
        assert.equal(request.body.model, "fixture-model");
        assert.equal(readFileSync(output, "utf8"), "### 功能新增\n- 测试条目");
    } finally {
        await new Promise((resolve) => server.close(resolve));
        rmSync(dir, { recursive: true, force: true });
    }
});
