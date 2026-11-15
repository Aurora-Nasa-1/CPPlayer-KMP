#!/usr/bin/env node

/**
 * generate_ai_changelog.mjs
 *
 * 使用 AI (OpenAI API / Gemini API) 根据 Git 提交记录自动生成
 * 结构清晰、专业严谨、高信息密度、面向终端用户的正式发版更新日志。
 *
 * 规范要求:
 * - 纯文本专业风格，严禁出现任何 Emoji 表情符号。
 * - 结构化分层输出（功能新增 / 问题修复 / 性能优化 / 交互与界面 / 其他改进）。
 * - 过滤内部工程琐碎提交（merge/chore/ci/bump/内部重构无感改动）。
 */

import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
const DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

const SYSTEM_PROMPT = `你是一个严谨、专业的发版更新日志整理助手。
请将提供的 Git 提交记录整理为结构规范、专业克制、面向最终使用用户的正式中文发版说明。

【格式规范】：
1. 严禁在标题、正文及条目中使用任何 Emoji 表情符号（如严禁使用任何火花、小虫、火箭等符号）。
2. 仅在存在对应变更时输出以下标准 Markdown 三级分类标题（无相关条目切勿输出该标题）：
### 功能新增
### 问题修复
### 性能优化
### 交互与界面
### 其他改进

【内容与表述要求】：
1. 彻底过滤内部工程与维护噪音：包括分支合并（merge）、依赖升级（bump）、持续集成（ci/workflow）、文档修正、构建脚本调整、内部微小重构或无外部感知的修改。
2. 每一项更新以 "- " 开头，语句严谨通顺，直截了当阐明对用户体验或功能带来的实质变化，避免使用技术黑话或内部函数名。
3. 关键功能模块、菜单路径或按键名称可使用 **粗体** 或 \`代码\` 标记。
4. 提交中若有明确关联的 Issue 或 Pull Request 编号（如 #123），统一保留在条目末尾，如 (#123)。
5. 严禁包含任何问候语、开场白、结语或解释说明，直接输出 Markdown 格式的更新条目。`;

function getCommits(prevTag, currentTag) {
  if (process.env.COMMITS && process.env.COMMITS.trim()) {
    return process.env.COMMITS.trim();
  }

  let range = "";
  if (prevTag && prevTag.trim()) {
    range = `${prevTag.trim()}..${currentTag || "HEAD"}`;
  } else {
    range = "-n 30 HEAD";
  }

  try {
    const cmd = `git log ${range} --no-merges --pretty=format:"- %s (%h)"`;
    const commits = execSync(cmd, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
    return commits;
  } catch (err) {
    console.error(`[AI-Changelog] Failed to extract git log: ${err.message}`);
    return "";
  }
}

/**
 * 彻底过滤字符串中的 Emoji 表情符号与非常用图形字符，确保正式整洁。
 */
function stripEmojis(text) {
  return text
    .replace(/[\u{1F300}-\u{1FAFF}\u{1F000}-\u{1F2FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, "")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}

/**
 * 把自定义 base URL 归一成 chat completions 端点。
 * 允许传 `https://host/v1`、带尾斜杠的地址，或完整的 `.../chat/completions`，
 * 避免更换兼容 OpenAI 的服务时重复拼接路径。
 */
export function resolveChatCompletionsUrl(baseUrl) {
  const base = (baseUrl ?? "").trim() || DEFAULT_OPENAI_BASE_URL;
  const normalized = base.replace(/\/+$/, "");
  return normalized.endsWith("/chat/completions")
    ? normalized
    : `${normalized}/chat/completions`;
}

export function resolveOpenAiConfig(env = process.env) {
  return {
    apiKey: env.OPENAI_API_KEY?.trim() || "",
    baseUrl: env.OPENAI_BASE_URL?.trim() || DEFAULT_OPENAI_BASE_URL,
    model: env.OPENAI_MODEL?.trim() || DEFAULT_OPENAI_MODEL,
  };
}

async function callOpenAI({ apiKey, baseUrl, model, commits }) {
  const url = resolveChatCompletionsUrl(baseUrl);
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model: model || DEFAULT_OPENAI_MODEL,
      temperature: 0.2,
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: `以下是待整理的提交历史：\n\n${commits}` },
      ],
    }),
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`OpenAI API error [${response.status}]: ${errText}`);
  }

  const data = await response.json();
  const rawContent = data.choices?.[0]?.message?.content?.trim();
  if (!rawContent) {
    throw new Error("OpenAI API returned empty response.");
  }
  return stripEmojis(rawContent);
}

async function callGemini({ apiKey, model, commits }) {
  const selectedModel = model || "gemini-1.5-flash";
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${selectedModel}:generateContent?key=${apiKey}`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      contents: [
        {
          role: "user",
          parts: [
            { text: `${SYSTEM_PROMPT}\n\n以下是待整理的提交历史：\n\n${commits}` },
          ],
        },
      ],
      generationConfig: {
        temperature: 0.2,
      },
    }),
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Gemini API error [${response.status}]: ${errText}`);
  }

  const data = await response.json();
  const rawContent = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
  if (!rawContent) {
    throw new Error("Gemini API returned empty response.");
  }
  return stripEmojis(rawContent);
}

async function main() {
  const args = process.argv.slice(2);
  const prevTag = args[0] || "";
  const currentTag = args[1] || "";
  const outputFile = args[2] || "";

  const commits = getCommits(prevTag, currentTag);
  if (!commits) {
    console.warn("[AI-Changelog] No commits found to generate changelog.");
    process.exit(2);
  }

  const openai = resolveOpenAiConfig();
  const geminiKey = process.env.GEMINI_API_KEY?.trim();

  if (!openai.apiKey && !geminiKey) {
    console.warn("[AI-Changelog] Neither OPENAI_API_KEY nor GEMINI_API_KEY is configured.");
    process.exit(3);
  }

  let changelog = "";
  if (openai.apiKey) {
    console.error(`[AI-Changelog] Requesting changelog from OpenAI-compatible endpoint (${openai.model})...`);
    changelog = await callOpenAI({
      apiKey: openai.apiKey,
      baseUrl: openai.baseUrl,
      model: openai.model,
      commits,
    });
  } else if (geminiKey) {
    console.error(`[AI-Changelog] Requesting changelog from Gemini (${process.env.GEMINI_MODEL || "gemini-1.5-flash"})...`);
    changelog = await callGemini({
      apiKey: geminiKey,
      model: process.env.GEMINI_MODEL,
      commits,
    });
  }

  if (outputFile) {
    fs.writeFileSync(outputFile, changelog, "utf8");
    console.error(`[AI-Changelog] Changelog successfully written to ${outputFile}`);
  } else {
    process.stdout.write(changelog + "\n");
  }
}

const invokedDirectly =
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (invokedDirectly) {
  main().catch((err) => {
    console.error(`[AI-Changelog] Error: ${err.message}`);
    process.exit(1);
  });
}
