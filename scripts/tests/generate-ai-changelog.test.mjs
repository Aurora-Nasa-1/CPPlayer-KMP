import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import http from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
    buildModelCandidates,
    findClosestModel,
    resolveChatCompletionsUrl,
    resolveModelsUrl,
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

test("上游模型列表端点与 chat completions 端点同源归一化", () => {
    assert.equal(resolveModelsUrl(""), "https://api.openai.com/v1/models");
    assert.equal(
        resolveModelsUrl(" https://api.example.com/v1/ "),
        "https://api.example.com/v1/models",
    );
    assert.equal(
        resolveModelsUrl("https://api.example.com/v1/chat/completions"),
        "https://api.example.com/v1/models",
    );
});

test("OpenAI 配置默认使用 deepseek-v4.1-flash-1017，且支持自定义", () => {
    assert.deepEqual(resolveOpenAiConfig({}), {
        apiKey: "",
        baseUrl: "https://api.openai.com/v1",
        model: "deepseek-v4.1-flash-1017",
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

test("上游近似匹配优先日期等价变体并返回原始 ID", () => {
    const models = [
        "gpt-4o-mini",
        "deepseek-v4.1-flash-lite",
        "deepseek-v4.1-flash-10-17",
    ];
    assert.equal(
        findClosestModel("deepseek-v4.1-flash-1017", models),
        "deepseek-v4.1-flash-10-17",
    );
    assert.equal(findClosestModel("gpt-4o-mini", models), null);
    assert.equal(
        findClosestModel("deepseek-v4.1-flash-1017", ["deepseek-v4.1-flash-1017"]),
        null,
    );
});

test("候选模型顺序为主模型、上游近似模型、自动回退模型且去重", () => {
    assert.deepEqual(
        buildModelCandidates(
            "deepseek-v4.1-flash-1017",
            ["deepseek-v4.1-flash-1017"],
            ["gpt-6-luna", "gemini-3.1-flash-lite"],
        ),
        ["deepseek-v4.1-flash-1017", "gpt-6-luna", "gemini-3.1-flash-lite"],
    );
    assert.deepEqual(
        buildModelCandidates(
            "custom-model",
            ["deepseek-v4.1-flash-lite"],
            ["gpt-6-luna"],
        ),
        ["custom-model", "deepseek-v4.1-flash-lite", "gpt-6-luna"],
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

function createFakeApi({ models = [], failModels = [] } = {}) {
    const attempts = [];
    const server = http.createServer((req, res) => {
        if (req.url === "/v1/models") {
            res.writeHead(200, { "content-type": "application/json" });
            res.end(JSON.stringify({ data: models.map((id) => ({ id })) }));
            return;
        }
        let body = "";
        req.on("data", (chunk) => (body += chunk));
        req.on("end", () => {
            const payload = JSON.parse(body || "{}");
            attempts.push({
                url: req.url,
                authorization: req.headers.authorization,
                payload,
            });
            if (failModels.includes(payload.model)) {
                res.writeHead(400, { "content-type": "application/json" });
                res.end(JSON.stringify({ error: { message: `model ${payload.model} unavailable` } }));
                return;
            }
            res.writeHead(200, { "content-type": "application/json" });
            res.end(JSON.stringify({
                choices: [{ message: { content: "- 测试条目" } }],
            }));
        });
    });
    return { server, attempts };
}

async function listen(server) {
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return server.address().port;
}

test("自定义 base URL 实际用于 chat completions 请求", async () => {
    const { server, attempts } = createFakeApi({ models: ["fixture-model"] });
    const port = await listen(server);

    const dir = mkdtempSync(path.join(tmpdir(), "ai-changelog-"));
    const output = path.join(dir, "notes.md");
    try {
        const result = await runScript(["", "HEAD", output], {
            COMMITS: "- feat: 自定义端点",
            OPENAI_API_KEY: "fixture-key",
            OPENAI_BASE_URL: `http://127.0.0.1:${port}/v1/`,
            OPENAI_MODEL: "fixture-model",
        });
        assert.equal(result.code, 0, result.stderr);
        const chat = attempts.find((entry) => entry.url === "/v1/chat/completions");
        assert.ok(chat);
        assert.equal(chat.authorization, "Bearer fixture-key");
        assert.equal(chat.payload.model, "fixture-model");
        assert.equal(readFileSync(output, "utf8"), "- 测试条目\n");
    } finally {
        await new Promise((resolve) => server.close(resolve));
        rmSync(dir, { recursive: true, force: true });
    }
});

test("主模型与上游近似模型失败后自动回退到 gpt-6-luna", async () => {
    const { server, attempts } = createFakeApi({
        models: ["deepseek-v4.1-flash-lite"],
        failModels: ["fixture-primary", "deepseek-v4.1-flash-lite"],
    });
    const port = await listen(server);

    const dir = mkdtempSync(path.join(tmpdir(), "ai-changelog-"));
    const output = path.join(dir, "notes.md");
    try {
        const result = await runScript(["", "HEAD", output], {
            COMMITS: "- feat: 自动回退",
            OPENAI_API_KEY: "fixture-key",
            OPENAI_BASE_URL: `http://127.0.0.1:${port}/v1`,
            OPENAI_MODEL: "fixture-primary",
        });
        assert.equal(result.code, 0, result.stderr);
        const models = attempts
            .filter((entry) => entry.url === "/v1/chat/completions")
            .map((entry) => entry.payload.model);
        assert.deepEqual(models, [
            "fixture-primary",
            "deepseek-v4.1-flash-lite",
            "gpt-6-luna",
        ]);
        assert.equal(readFileSync(output, "utf8"), "- 测试条目\n");
    } finally {
        await new Promise((resolve) => server.close(resolve));
        rmSync(dir, { recursive: true, force: true });
    }
});

test("AI_FETCH_MODEL_LIST=0 可关闭上游模型列表拉取", async () => {
    const { server, attempts } = createFakeApi({ models: ["fixture-model"] });
    const port = await listen(server);

    try {
        const result = await runScript(["", "HEAD"], {
            COMMITS: "- feat: 跳过模型列表",
            OPENAI_API_KEY: "fixture-key",
            OPENAI_BASE_URL: `http://127.0.0.1:${port}/v1`,
            OPENAI_MODEL: "fixture-model",
            AI_FETCH_MODEL_LIST: "0",
        });
        assert.equal(result.code, 0, result.stderr);
        assert.equal(attempts.some((entry) => entry.url === "/v1/models"), false);
        assert.equal(result.stdout.trim(), "- 测试条目");
    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
});
