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
    JAVA_BIN="$REPO_ROOT/app/build/compose/binaries/main/app/CPPlayer/lib/runtime/bin/java"
fi
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

APP_IMAGE_DIR="$REPO_ROOT/app/build/compose/binaries/main/app/CPPlayer"
# ⚠️ 在 Linux 上，jpackage 生成的标准目录结构是 lib/app；而在 Windows 上是 app/。兼容二者。
if [[ -d "$APP_IMAGE_DIR/lib/app" ]]; then
    APP_DIR="$APP_IMAGE_DIR/lib/app"
elif [[ -d "$APP_IMAGE_DIR/app" ]]; then
    APP_DIR="$APP_IMAGE_DIR/app"
else
    APP_DIR="$APP_IMAGE_DIR"
fi

if [[ ! -d "$APP_DIR" || ! -f "$APP_DIR/CPPlayer.cfg" ]]; then
    echo "[2/4] Assembling distributable image..."
    (cd "$REPO_ROOT" && ./gradlew :app:createDistributable -x test --quiet)
    if [[ -d "$APP_IMAGE_DIR/lib/app" ]]; then
        APP_DIR="$APP_IMAGE_DIR/lib/app"
    elif [[ -d "$APP_IMAGE_DIR/app" ]]; then
        APP_DIR="$APP_IMAGE_DIR/app"
    fi
else
    echo "[2/4] Reusing existing distributable app image at: $APP_DIR"
fi

# ⚠️ 从 CPPlayer.cfg 读取精确的类路径顺序。
# 旧实现直接用通配符 "$APP_DIR/*"，顺序由文件系统决定，与 .cfg 里的顺序可能不一致；
# AOT Cache 对类路径顺序极其敏感 —— 顺序不对 HotSpot 会报
# "shared class paths mismatch" 并静默忽略缓存（启动没有加速但也不会崩溃）。
# 与 generate-aot-cache.ps1 对齐：都从 .cfg 里读 app.classpath。
CFG_FILE="$APP_DIR/CPPlayer.cfg"
if [[ -f "$CFG_FILE" ]]; then
    echo "[3/4] Reading classpath from CPPlayer.cfg..."
    # 从 .cfg 解析 app.classpath= 行，把 $APPDIR 替换为实际路径，冒号分隔
    EFFECTIVE_CP="$(grep '^app.classpath=' "$CFG_FILE" \
        | sed 's/^app\.classpath=//' \
        | sed "s|\\\$APPDIR|$APP_DIR|g" \
        | tr '\n' ':' \
        | sed 's/:$//')"
    if [[ -z "$EFFECTIVE_CP" ]]; then
        echo "  Warning: app.classpath not found in CPPlayer.cfg, falling back to glob." >&2
        EFFECTIVE_CP="$APP_DIR/*"
    fi
else
    echo "  Warning: CPPlayer.cfg not found, falling back to glob classpath." >&2
    EFFECTIVE_CP="$APP_DIR/*"
fi

echo "  Effective classpath: $EFFECTIVE_CP"

echo "[3/4] Performing AOT training run..."
rm -f "$OUTPUT_FILE"

JVM_ARGS=(
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+UseShenandoahGC"
    "-XX:ShenandoahGCMode=generational"
    "-XX:+ShenandoahUncommit"
    "-XX:+UseCompactObjectHeaders"
    "--enable-native-access=ALL-UNNAMED"
    "-Xms128m"
    "-Xmx512m"
    "-Dskiko.library.path=$APP_DIR"
    "-Dcp.player.aotWarmup=true"
    "-Dcp.player.releaseChannel=stable"
    "-cp" "$EFFECTIVE_CP"
)

# Test for JEP 514 support
if "$JAVA_BIN" -XX:AOTCacheOutput=/dev/null -version &>/dev/null; then
    echo "  Using JEP 514 One-Shot Ahead-of-Time Command-Line Ergonomics..."
    timeout 60s "$JAVA_BIN" "-XX:AOTCacheOutput=$OUTPUT_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
    # JEP 514 在父进程退出后由子进程异步写 .aot 文件；等待最多 30 秒让其完成。
    CONFIG_FILE="${OUTPUT_FILE}.config"
    deadline=$((SECONDS + 30))
    while [[ -f "$CONFIG_FILE" && $SECONDS -lt $deadline ]]; do
        sleep 0.5
    done
else
    echo "  Falling back to JEP 483 two-step workflow..."
    CONFIG_FILE="${OUTPUT_FILE%.*}.aotconf"
    timeout 60s "$JAVA_BIN" "-XX:AOTMode=record" "-XX:AOTConfiguration=$CONFIG_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
    if [[ -f "$CONFIG_FILE" ]]; then
        timeout 60s "$JAVA_BIN" "-XX:AOTMode=create" "-XX:AOTConfiguration=$CONFIG_FILE" "-XX:AOTCache=$OUTPUT_FILE" "-cp" "$EFFECTIVE_CP" || true
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
