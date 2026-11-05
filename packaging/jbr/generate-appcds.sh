#!/usr/bin/env bash
# ==============================================================================
# Generate AppCDS (Application Class Data Sharing) Archive for CPPlayer Desktop
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_FILE="${1:-${SCRIPT_DIR}/app-cds.jsa}"

echo "=========================================================="
echo "  CPPlayer AppCDS Archive Generator (Linux)"
echo "  Destination: ${OUTPUT_FILE}"
echo "=========================================================="

cd "${REPO_ROOT}"

echo "[1/3] Building desktop distributable..."
./gradlew :app:createDistributable -x test --quiet

APP_DIR="${REPO_ROOT}/app/build/compose/binaries/main/app/CPPlayer"
JAVA_BIN="${APP_DIR}/runtime/bin/java"
if [ ! -f "${JAVA_BIN}" ]; then
    JAVA_BIN="${REPO_ROOT}/.jbr/linux-x64/bin/java"
fi

if [ ! -f "${JAVA_BIN}" ]; then
    echo "Error: Java executable not found."
    exit 1
fi

echo "[2/3] Performing warmup execution to dump classes..."
rm -f "${OUTPUT_FILE}"

"${JAVA_BIN}" \
    -XX:ArchiveClassesAtExit="${OUTPUT_FILE}" \
    -Dcp.player.cdsDump=true \
    -cp "${APP_DIR}/app/*" \
    cp.player.app.MainKt &
PID=$!

sleep 4
kill -SIGTERM "${PID}" 2>/dev/null || true
wait "${PID}" 2>/dev/null || true

echo "[3/3] Checking AppCDS result..."
if [ -f "${OUTPUT_FILE}" ]; then
    SIZE=$(du -h "${OUTPUT_FILE}" | cut -f1)
    echo "Successfully generated AppCDS archive (${SIZE}): ${OUTPUT_FILE}"
else
    echo "Warning: AppCDS archive generation did not output a file."
fi
