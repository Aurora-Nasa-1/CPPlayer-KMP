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
 * - 默认模型 deepseek-v4.1-flash-1017；可选从上游 /models 拉取列表并挑选近似 deepseek-v4.1-flash 的模型。
 * - 主模型失败时自动回退到 gpt-6-luna、gemini-3.1-flash-lite（以及 Gemini 端点作为最后兜底）。
 */

import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
const DEFAULT_OPENAI_MODEL = "deepseek-v4.1-flash-1017";
const DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
/** 上游模型列表里要找的近似目标（不带日期后缀）。 */
const MODEL_FAMILY_HINT = "deepseek-v4.1-flash";
/** OpenAI 兼容端点的自动回退模型，按优先级排列。 */
const OPENAI_FALLBACK_MODELS = ["gpt-6-luna", "gemini-3.1-flash-lite"];
/** Gemini 原生端点的自动回退模型。 */
const GEMINI_FALLBACK_MODELS = ["gemini-3.1-flash-lite"];
const MODEL_LIST_TIMEOUT_MS = 3000;
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

function gitRevision(ref, cwd) {
  try {
    return execSync(`git rev-parse --verify "${ref}^{commit}"`, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      cwd,
    }).trim();
  } catch {
    return "";
  }
}

export function getCommits(prevTag, currentTag, cwd) {
  if (process.env.COMMITS && process.env.COMMITS.trim()) {
    return process.env.COMMITS.trim();
  }

  const current = (currentTag || "HEAD").trim();
  const previous = (prevTag || "").trim();
  const range = previous ? `${previous}..${current}` : `-n 30 ${current}`;

  const readRange = (spec) => {
    try {
      const cmd = `git log ${spec} --no-merges --pretty=format:"%s%x1f%h%x1f%b%x1e"`;
      const raw = execSync(cmd, {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
        cwd,
      });
      return formatRawGitLog(raw);
    } catch (err) {
      console.error(`[AI-Changelog] Failed to extract git log (${spec}): ${err.message}`);
      return "";
    }
  };

  const commits = readRange(range);
  if (commits) return commits;

  // 补发/重打标签时两个 tag 会指向同一提交，prev..current 自然为空。
  // 此时总结当前 tag 自身那条提交，避免把 AI 生成整个跳过、发布说明落到空字符串。
  if (previous) {
    const prevSha = gitRevision(previous, cwd);
    const currentSha = gitRevision(current, cwd);
    if (prevSha && currentSha && prevSha === currentSha) {
      console.error(
        `[AI-Changelog] "${previous}" and "${current}" point to the same commit (${currentSha.slice(0, 7)}); summarizing the tagged commit instead.`
      );
      const single = readRange(`-n 1 ${current}`);
      if (single) return single;
    }
  }

  return "";
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
 * 归一 base URL：去尾斜杠；裸域名/根路径（如 `https://host`）自动补 `/v1`。
 * 已带版本段（`/v1`、`/api/v2` 等）或自定义路径的地址原样保留，
 * 避免误伤刻意使用非 `/v1` 路径的 OpenAI 兼容服务。
 */
function normalizeBaseUrl(baseUrl) {
  const base = (baseUrl ?? "").trim() || DEFAULT_OPENAI_BASE_URL;
  const normalized = base.replace(/\/+$/, "");
  const withoutProtocol = normalized.replace(/^[a-z][a-z\d+.-]*:\/\//i, "");
  const path = withoutProtocol.includes("/")
    ? withoutProtocol.slice(withoutProtocol.indexOf("/"))
    : "";
  return !path || path === "/" ? `${normalized}/v1` : normalized;
}

/**
 * 把自定义 base URL 归一成 chat completions 端点。
 * 允许传 `https://host`（自动补 /v1）、`https://host/v1`、带尾斜杠的地址，
 * 或完整的 `.../chat/completions`，避免更换兼容服务时重复拼接路径。
 */
export function resolveChatCompletionsUrl(baseUrl) {
  const normalized = normalizeBaseUrl(baseUrl);
  return normalized.endsWith("/chat/completions")
    ? normalized
    : `${normalized}/chat/completions`;
}

/** 与 resolveChatCompletionsUrl 同源，把 base URL 归一成上游模型列表端点。 */
export function resolveModelsUrl(baseUrl) {
  const normalized = normalizeBaseUrl(baseUrl);
  const root = normalized.endsWith("/chat/completions")
    ? normalized.slice(0, -"/chat/completions".length)
    : normalized;
  return `${root}/models`;
}

export function resolveOpenAiConfig(env = process.env) {
  return {
    apiKey: env.OPENAI_API_KEY?.trim() || "",
    baseUrl: env.OPENAI_BASE_URL?.trim() || DEFAULT_OPENAI_BASE_URL,
    model: env.OPENAI_MODEL?.trim() || DEFAULT_OPENAI_MODEL,
  };
}

/**
 * 从上游 OpenAI 兼容 /models 端点拉取模型 ID。
 * 这是 best-effort：失败、超时或非 JSON 都返回空数组，绝不阻断更新日志生成。
 */
export async function fetchUpstreamModels(
  { apiKey, baseUrl },
  timeoutMs = MODEL_LIST_TIMEOUT_MS
) {
  try {
    const response = await fetch(resolveModelsUrl(baseUrl), {
      headers: apiKey ? { Authorization: `Bearer ${apiKey}` } : {},
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      console.error(
        `[AI-Changelog] Upstream model list request failed [${response.status}], continuing with local candidates.`
      );
      return [];
    }
    const payload = await response.json();
    const entries = Array.isArray(payload?.data) ? payload.data : [];
    const models = entries
      .map((entry) => (typeof entry === "string" ? entry : entry?.id))
      .filter((id) => typeof id === "string" && id.trim())
      .map((id) => id.trim());
    console.error(`[AI-Changelog] Fetched ${models.length} model(s) from upstream.`);
    return models;
  } catch (err) {
    console.error(
      `[AI-Changelog] Upstream model list unavailable (${err.message}), continuing with local candidates.`
    );
    return [];
  }
}

function normalizeModelName(name) {
  return String(name ?? "").trim().toLowerCase().replace(/[\s_]+/g, "-");
}

/** 去掉日期/版本类后缀，得到模型家族名（deepseek-v4.1-flash-1017 → deepseek-v4.1-flash）。 */
function modelFamily(name) {
  return normalizeModelName(name).replace(
    /-(?:\d{1,8}(?:-\d{1,2})?|latest|preview|beta|exp(?:erimental)?)$/,
    ""
  );
}

/** 提取末尾日期串：deepseek-v4.1-flash-10-17 → 1017。 */
function modelDateSuffix(name) {
  const match = normalizeModelName(name).match(/-(\d{1,8}(?:-\d{1,2})?)$/);
  return match ? match[1].replace(/-/g, "") : "";
}

/** 去掉末尾日期串：deepseek-v4.1-flash-10-17 → deepseek-v4.1-flash。 */
function modelBaseName(name) {
  return normalizeModelName(name).replace(/-\d{1,8}(?:-\d{1,2})?$/, "");
}

function modelNameScore(wanted, candidate) {
  if (!wanted || !candidate) return 0;
  if (candidate === wanted) return 100;
  if (candidate.startsWith(wanted)) {
    return 90 - (candidate.length - wanted.length) * 0.1;
  }
  if (candidate.includes(wanted)) {
    return 80 - (candidate.length - wanted.length) * 0.1;
  }
  if (wanted.includes(candidate)) {
    return 70 - (wanted.length - candidate.length) * 0.1;
  }
  const wantedTokens = new Set(wanted.split(/[-./]/).filter(Boolean));
  const candidateTokens = new Set(candidate.split(/[-./]/).filter(Boolean));
  let shared = 0;
  for (const token of wantedTokens) {
    if (candidateTokens.has(token)) shared += 1;
  }
  return shared === 0
    ? 0
    : (shared / Math.max(wantedTokens.size, candidateTokens.size)) * 50;
}

/**
 * 从上游模型列表里找近似 deepseek-v4.1-flash 的模型 ID。
 * 返回的是上游原始 ID（供 API 直接使用）；配置值已在列表中或没有相近项时返回 null。
 */
export function findClosestModel(target, availableModels) {
  const targetKey = normalizeModelName(target);
  const targetFamily = modelFamily(target) || modelFamily(MODEL_FAMILY_HINT);
  const hintFamily = modelFamily(MODEL_FAMILY_HINT);
  const targetDate = modelDateSuffix(target);
  const targetBase = modelBaseName(target);

  let bestRaw = null;
  let bestScore = 0;
  for (const raw of availableModels ?? []) {
    const key = normalizeModelName(raw);
    if (!key) continue;
    let score = Math.max(
      modelNameScore(targetFamily, key),
      modelNameScore(hintFamily, key)
    );
    // 日期等价的同族变体（1017 / 10-17）优先于 -lite 等泛化变体。
    if (
      targetDate &&
      modelDateSuffix(raw) === targetDate &&
      modelBaseName(raw) === targetBase
    ) {
      score = Math.max(score, 95);
    }
    // 当前配置值已存在时优先保持原样，避免无意义切换。
    if (key === targetKey) score = Math.max(score, 110);
    if (
      score > bestScore ||
      (score === bestScore &&
        score > 0 &&
        bestRaw &&
        key.length < normalizeModelName(bestRaw).length)
    ) {
      bestRaw = raw;
      bestScore = score;
    }
  }

  if (!bestRaw || normalizeModelName(bestRaw) === targetKey) return null;
  return bestRaw;
}

/** 构造去重后的模型尝试顺序：主模型 → 上游近似模型 → 自动回退模型。 */
export function buildModelCandidates(primaryModel, availableModels, fallbackModels) {
  const closest = findClosestModel(primaryModel, availableModels);
  const ordered = [primaryModel, closest, ...(fallbackModels ?? [])];
  const seen = new Set();
  return ordered
    .map((model) => String(model ?? "").trim())
    .filter((model) => {
      if (!model || seen.has(model)) return false;
      seen.add(model);
      return true;
    });
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

/** 逐个尝试候选模型：任一成功即返回，全部失败时抛出最后一个错误。 */
async function generateWithCandidates(label, candidates, invoke) {
  let lastError = null;
  for (const model of candidates) {
    try {
      console.error(`[AI-Changelog] Requesting changelog from ${label} (${model})...`);
      return await invoke(model);
    } catch (err) {
      lastError = err;
      console.error(`[AI-Changelog] ${label} model ${model} failed: ${err.message}`);
    }
  }
  throw lastError ?? new Error(`No ${label} model candidates available.`);
}

async function generateWithOpenAi(config, commits, availableModels) {
  const candidates = buildModelCandidates(
    config.model,
    availableModels,
    OPENAI_FALLBACK_MODELS
  );
  return generateWithCandidates("OpenAI-compatible", candidates, (model) =>
    callOpenAI({
      apiKey: config.apiKey,
      baseUrl: config.baseUrl,
      model,
      commits,
    })
  );
}

async function generateWithGemini(apiKey, primaryModel, commits) {
  const candidates = buildModelCandidates(
    primaryModel,
    [],
    GEMINI_FALLBACK_MODELS
  );
  return generateWithCandidates("Gemini", candidates, (model) =>
    callGemini({ apiKey, model, commits })
  );
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

  const geminiModel = process.env.GEMINI_MODEL?.trim() || DEFAULT_GEMINI_MODEL;
  let changelog = "";
  let lastError = null;

  if (openai.apiKey) {
    const fetchEnabled = !/^(?:0|false|no)$/i.test(
      process.env.AI_FETCH_MODEL_LIST?.trim() ?? ""
    );
    const upstreamModels = fetchEnabled ? await fetchUpstreamModels(openai) : [];
    try {
      changelog = await generateWithOpenAi(openai, commits, upstreamModels);
    } catch (err) {
      lastError = err;
    }
    // OpenAI 兼容端点的所有候选都失败时，Gemini 端点作为最后一道兜底。
    if (!changelog && geminiKey) {
      try {
        changelog = await generateWithGemini(geminiKey, geminiModel, commits);
      } catch (err) {
        lastError = err;
      }
    }
  } else {
    try {
      changelog = await generateWithGemini(geminiKey, geminiModel, commits);
    } catch (err) {
      lastError = err;
    }
  }

  if (!changelog) {
    throw lastError ?? new Error("No changelog was generated.");
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
