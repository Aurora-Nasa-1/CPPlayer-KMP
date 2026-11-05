#!/usr/bin/env bash
# ==============================================================================
# Generates an AOT (Ahead-of-Time Class Loading & Linking - JEP 483 / 514) cache
# for CPPlayer Desktop on Linux / macOS.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_FILE="${1:-"$SCRIPT_DIR/app-aot.aot"}"

echo "=========================================================="
echo "  CPPlayer AOT Cache Generator (JDK 25 / JEP 483 & 514)  "
echo "  Destination: $OUTPUT_FILE"
echo "=========================================================="

JAVA_BIN="${CP_JBR_HOME:-}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then
    JAVA_BIN="$REPO_ROOT/app/build/compose/binaries/main/app/CPPlayer/runtime/bin/java"
fi
if [[ ! -x "$JAVA_BIN" ]]; then
    JAVA_BIN="$REPO_ROOT/dist-jbr/linux-x64/bin/java"
fi
if [[ ! -x "$JAVA_BIN" ]]; then
    JAVA_BIN="$(which java || true)"
fi

if [[ ! -x "$JAVA_BIN" ]]; then
    echo "Error: Could not locate a valid java executable. Set CP_JBR_HOME or ensure java is in PATH." >&2
    exit 1
fi

echo "[1/4] Probing JVM capabilities at: $JAVA_BIN"
"$JAVA_BIN" -version

APP_DIR="$REPO_ROOT/app/build/compose/binaries/main/app/CPPlayer/app"
if [[ ! -d "$APP_DIR" ]]; then
    echo "[2/4] Assembling distributable image..."
    (cd "$REPO_ROOT" && ./gradlew :app:createDistributable -x test --quiet)
else
    echo "[2/4] Reusing existing distributable app image at: $APP_DIR"
fi

echo "[3/4] Performing AOT training run..."
rm -f "$OUTPUT_FILE"

JVM_ARGS=(
    "--enable-native-access=ALL-UNNAMED"
    "-Xms128m"
    "-Xmx512m"
    "-Dskiko.library.path=$APP_DIR"
    "-Dcp.player.aotWarmup=true"
    "-Dcp.player.releaseChannel=debug"
    "-cp" "$APP_DIR/*"
)

# Test for JEP 514 support
if "$JAVA_BIN" -XX:AOTCacheOutput=/dev/null -version &>/dev/null; then
    echo "  Using JEP 514 One-Shot Ahead-of-Time Command-Line Ergonomics..."
    "$JAVA_BIN" "-XX:AOTCacheOutput=$OUTPUT_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
else
    echo "  Falling back to JEP 483 two-step workflow..."
    CONFIG_FILE="${OUTPUT_FILE%.*}.aotconf"
    "$JAVA_BIN" "-XX:AOTMode=record" "-XX:AOTConfiguration=$CONFIG_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
    if [[ -f "$CONFIG_FILE" ]]; then
        "$JAVA_BIN" "-XX:AOTMode=create" "-XX:AOTConfiguration=$CONFIG_FILE" "-XX:AOTCache=$OUTPUT_FILE" "-cp" "$APP_DIR/*"
        rm -f "$CONFIG_FILE"
    fi
fi

echo "[4/4] Verifying generated AOT cache..."
if [[ -f "$OUTPUT_FILE" ]]; then
    SIZE_MB=$(du -m "$OUTPUT_FILE" | cut -f1)
    echo "=========================================================="
    echo "  AOT Cache successfully generated: ${SIZE_MB} MB"
    echo "  Mount flag: -XX:AOTCache=$OUTPUT_FILE"
    echo "=========================================================="
else
    echo "Warning: AOT cache was not created." >&2
fi
