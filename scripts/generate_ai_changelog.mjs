#!/usr/bin/env node

/**
 * generate_ai_changelog.mjs
 *
 * 使用 AI (OpenAI 兼容接口 / Gemini API) 根据 Git 提交记录自动生成
 * 简洁、专业、面向终端用户的扁平化更新日志。
 *
 * 规范要求:
 * - 纯文本风格，严禁出现任何 Emoji 表情符号。
 * - 无分类标题，直接输出简洁的 `- 新增 / - 优化了 / - 修复了` 列表。
 * - 强制要求模型使用 <content>...</content> 包裹正文，并结合代码层二次清洗，杜绝开场白与结语污染。
 * - 自动合并同类提交，过滤内部工程琐碎提交。
 */

import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
const DEFAULT_OPENAI_MODEL = "deepseek-v4.1-flash-10-17";
const DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
const MAX_BODY_LENGTH = 160;

const SYSTEM_PROMPT = `你是一个严谨、简洁的发版更新日志整理助手。
请将提供的 Git 提交记录整理为面向最终使用用户的中文更新日志。

【格式规范】：
1. 必须将最终的更新日志严格包裹在 <content> 和 </content> 标签之间，标签外不要输出任何多余内容。
2. 严禁使用任何 Emoji 表情符号，严禁输出任何分级标题（不要使用 #、##、### 等标题），严禁使用 Markdown 代码块包裹。
3. 标签内直接输出扁平的无序列表，每条以 "- " 开头，采用简洁直白的动宾短语，统一以「新增」「优化了」「修复了」「调整了」「移除了」等动词开头（例如：- 新增 xxx、- 优化了 xxx、- 修复了 xxx）。
4. 列表内部按「新增 > 优化 > 修复 > 调整/移除」的顺序自然排列，每条尽量控制在一句话以内。
5. 关键功能、菜单路径或快捷键可使用 **粗体** 或 \`代码\` 标记；若提交中有关联的 Issue/PR 编号（如 #123），统一保留在条目末尾，如 (#123)。

【内容要求】：
1. 合并同类项：多条提交围绕同一功能开发或反复修复同一问题时，必须合并为一条最终结果。
2. 彻底过滤内部噪音：包括分支合并（merge）、依赖升级（bump）、CI/工作流、构建脚本、文档微调、内部无感重构等。
3. 严禁脑补：若提交信息含糊且缺乏上下文，宁可简要概括或跳过，不要编造未提及的细节。
4. 兜底规则：若所有提交均无用户可感知的变化，仅在标签内输出一行：- 例行维护与稳定性改进。`;

function formatRawGitLog(raw) {
  return raw
    .split("\x1e")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const [subject = "", hash = "", body = ""] = entry.split("\x1f");
      const cleanBody = body
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(
          (line) =>
            line &&
            !/^(Co-authored-by|Signed-off-by|Reviewed-by|Change-Id):/i.test(line)
        )
        .join(" ")
        .slice(0, MAX_BODY_LENGTH)
        .trim();

      return cleanBody
        ? `- ${subject.trim()} (${hash.trim()}) | 详情: ${cleanBody}`
        : `- ${subject.trim()} (${hash.trim()})`;
    })
    .join("\n");
}

function getCommits(prevTag, currentTag) {
  if (process.env.COMMITS && process.env.COMMITS.trim()) {
    return process.env.COMMITS.trim();
  }

  const range =
    prevTag && prevTag.trim()
      ? `${prevTag.trim()}..${currentTag || "HEAD"}`
      : "-n 30 HEAD";

  try {
    const cmd = `git log ${range} --no-merges --pretty=format:"%s%x1f%h%x1f%b%x1e"`;
    const raw = execSync(cmd, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return formatRawGitLog(raw);
  } catch (err) {
    console.error(`[AI-Changelog] Failed to extract git log: ${err.message}`);
    return "";
  }
}

/**
 * 清洗模型输出：
 * 1. 先移除思考标签（避免思考过程中提到的 <content> 干扰匹配）
 * 2. 提取 <content>...</content> 内部正文（若模型漏写标签则自动降级）
 * 3. 剥离可能误加的代码块、Emoji，并仅保留规范的 "- " 列表项
 */
function sanitizeOutput(text) {
  const withoutThink = text.replace(/<think>[\s\S]*?<\/think>/gi, "").trim();

  const match = withoutThink.match(/<content>([\s\S]*?)<\/content>/i);
  const extracted = match ? match[1] : withoutThink;

  const cleaned = extracted
    .trim()
    .replace(/^```(?:markdown|md)?\s*\r?\n/i, "")
    .replace(/\r?\n```\s*$/, "")
    .replace(
      /[\u{1F300}-\u{1FAFF}\u{1F000}-\u{1F2FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{FE00}-\u{FE0F}]/gu,
      ""
    );

  const bullets = cleaned
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => /^[-*•]\s+/.test(line))
    .map((line) =>
      line
        .replace(/^[*•]\s+/, "- ")
        .replace(/(?<=\S)[ \t]{2,}/g, " ")
    );

  if (bullets.length === 0) {
    throw new Error(`Model output did not contain valid changelog bullets:\n${text}`);
  }

  return bullets.join("\n");
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
  return sanitizeOutput(rawContent);
}

async function callGemini({ apiKey, model, commits }) {
  const selectedModel = model?.trim() || DEFAULT_GEMINI_MODEL;
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${selectedModel}:generateContent?key=${apiKey}`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      systemInstruction: {
        parts: [{ text: SYSTEM_PROMPT }],
      },
      contents: [
        {
          role: "user",
          parts: [{ text: `以下是待整理的提交历史：\n\n${commits}` }],
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
  return sanitizeOutput(rawContent);
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
    const geminiModel = process.env.GEMINI_MODEL?.trim() || DEFAULT_GEMINI_MODEL;
    console.error(`[AI-Changelog] Requesting changelog from Gemini (${geminiModel})...`);
    changelog = await callGemini({
      apiKey: geminiKey,
      model: geminiModel,
      commits,
    });
  }

  if (outputFile) {
    fs.writeFileSync(outputFile, changelog + "\n", "utf8");
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
