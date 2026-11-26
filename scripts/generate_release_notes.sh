#!/usr/bin/env bash
set -euo pipefail

CURRENT_TAG="${1:-${GITHUB_REF_NAME:-}}"
OUTPUT_FILE="${2:-}"

# 如果没有传 CURRENT_TAG，尝试获取 HEAD tag
if [ -z "$CURRENT_TAG" ]; then
    CURRENT_TAG=$(git describe --tags --exact-match HEAD 2>/dev/null || echo "")
fi

echo "Resolving changelog for tag: ${CURRENT_TAG:-HEAD}" >&2

# 寻找上一个版本 tag。
# 关键：按提交祖先关系找（git describe <tag>^），不能只按版本号排序——
# 重打/补发 tag 时新版本号可能更旧（如 v1.12.2 晚于 v1.12.3 补发），
# 版本号排序会选到当前 tag 的后代，导致区间反向为空、compare 链接颠倒。
# 若当前 tag 的父提交没有同系列 tag（首个版本），退回按版本号取最新的那个。
PREV_TAG=""
if [ -n "$CURRENT_TAG" ]; then
    if [[ "$CURRENT_TAG" == debug-v* ]]; then
        tag_pattern='debug-v*'
    else
        tag_pattern='v*'
    fi
    PREV_TAG=$(git describe --tags --abbrev=0 --match "$tag_pattern" "${CURRENT_TAG}^" 2>/dev/null || true)
    if [ -z "$PREV_TAG" ] || [ "$PREV_TAG" = "$CURRENT_TAG" ]; then
        PREV_TAG=$(git tag -l "$tag_pattern" --sort=-v:refname | grep -F -v -x "$CURRENT_TAG" | head -n 1 || true)
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
        range_notes="$(git log "$RANGE" --no-merges --pretty=format:'### %s (%h)%n%n%b')"
        if [ -n "$range_notes" ]; then
            echo "$range_notes"
        else
            # 两个 tag 指向同一提交时区间为空，退回当前 tag 自身那条提交。
            echo "Tag range ${RANGE} is empty; falling back to the tagged commit." >&2
            git log -n 1 "${CURRENT_TAG:-HEAD}" --no-merges --pretty=format:'### %s (%h)%n%n%b'
        fi
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
