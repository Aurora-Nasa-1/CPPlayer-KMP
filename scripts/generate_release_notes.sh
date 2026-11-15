#!/usr/bin/env bash
set -euo pipefail

CURRENT_TAG="${1:-${GITHUB_REF_NAME:-}}"
OUTPUT_FILE="${2:-}"

# 如果没有传 CURRENT_TAG，尝试获取 HEAD tag
if [ -z "$CURRENT_TAG" ]; then
    CURRENT_TAG=$(git describe --tags --exact-match HEAD 2>/dev/null || echo "")
fi

echo "Resolving changelog for tag: ${CURRENT_TAG:-HEAD}" >&2

# 寻找上一个版本 tag
PREV_TAG=""
if [ -n "$CURRENT_TAG" ]; then
    # 若是 debug-v* 标签，找上一个 debug-v*；若是 v* 标签，找上一个 v*
    if [[ "$CURRENT_TAG" == debug-v* ]]; then
        PREV_TAG=$(git tag -l 'debug-v*' --sort=-v:refname | grep -F -v -x "$CURRENT_TAG" | head -n 1 || true)
    else
        PREV_TAG=$(git tag -l 'v*' --sort=-v:refname | grep -F -v -x "$CURRENT_TAG" | grep -v "^debug-" | head -n 1 || true)
    fi
else
    PREV_TAG=$(git tag -l 'v*' --sort=-v:refname | grep -v "^debug-" | head -n 1 || true)
fi

echo "Previous tag: ${PREV_TAG:-none}" >&2

generate() {
    echo "## 更新内容 (What's Changed)"
    echo ""

    local ai_changelog=""
    local script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    local ai_script="${script_dir}/generate_ai_changelog.mjs"

    # 若配置了 AI 密钥且存在 node 脚本，优先尝试由 AI 总结精炼的用户侧更新日志
    if [[ (-n "${OPENAI_API_KEY:-}" || -n "${GEMINI_API_KEY:-}") && -f "$ai_script" ]] && command -v node >/dev/null 2>&1; then
        echo "Attempting to generate user-facing changelog via AI SDK..." >&2
        local ai_log
        ai_log="$(mktemp)"
        if ai_changelog=$(node "$ai_script" "$PREV_TAG" "${CURRENT_TAG:-HEAD}" 2>"$ai_log"); then
            cat "$ai_log" >&2
        else
            echo "::warning::AI changelog generation failed, falling back to git log. Error: $(cat "$ai_log")" >&2
            ai_changelog=""
        fi
        rm -f "$ai_log"
    fi

    if [ -n "$ai_changelog" ]; then
        echo "$ai_changelog"
        echo ""
    elif [ -n "$PREV_TAG" ] && git rev-parse -q --verify "$PREV_TAG" >/dev/null; then
        RANGE="${PREV_TAG}..${CURRENT_TAG:-HEAD}"
        git log "$RANGE" --no-merges --pretty=format:'### %s (%h)%n%n%b'
        echo ""
    else
        git log -n 15 --no-merges --pretty=format:'### %s (%h)%n%n%b'
        echo ""
    fi

    echo "---"
    REPO="${GITHUB_REPOSITORY:-Aurora-Nasa-1/CPPlayer-KMP}"
    if [ -n "$PREV_TAG" ] && git rev-parse -q --verify "$PREV_TAG" >/dev/null; then
        echo "**完整变更记录**: https://github.com/${REPO}/compare/${PREV_TAG}...${CURRENT_TAG:-HEAD}"
    else
        echo "**完整变更记录**: https://github.com/${REPO}/commits/${CURRENT_TAG:-HEAD}"
    fi
}

if [ -n "$OUTPUT_FILE" ]; then
    mkdir -p "$(dirname "$OUTPUT_FILE")"
    generate > "$OUTPUT_FILE"
    echo "Changelog written to $OUTPUT_FILE" >&2
else
    generate
fi
