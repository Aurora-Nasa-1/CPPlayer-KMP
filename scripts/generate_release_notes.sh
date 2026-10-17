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
    if [ -n "$PREV_TAG" ] && git rev-parse -q --verify "$PREV_TAG" >/dev/null; then
        RANGE="${PREV_TAG}..${CURRENT_TAG:-HEAD}"
        git log "$RANGE" --no-merges --pretty=format:'### %s (%h)%n%n%b'
        echo ""
        echo "---"
        REPO="${GITHUB_REPOSITORY:-Aurora-Nasa-1/CPPlayer-KMP}"
        echo "**完整变更记录**: https://github.com/${REPO}/compare/${PREV_TAG}...${CURRENT_TAG:-HEAD}"
    else
        git log -n 15 --no-merges --pretty=format:'### %s (%h)%n%n%b'
    fi
}

if [ -n "$OUTPUT_FILE" ]; then
    mkdir -p "$(dirname "$OUTPUT_FILE")"
    generate > "$OUTPUT_FILE"
    echo "Changelog written to $OUTPUT_FILE" >&2
else
    generate
fi
