#!/usr/bin/env bash
# ==============================================================================
# Generates an AOT (Ahead-of-Time Class Loading & Linking - JEP 483 / 514) cache
# for CPPlayer Desktop on Linux / macOS.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_FILE="${1:-"$SCRIPT_DIR/app-aot.aot"}"
VERSION_NAME="${2:-${APP_VERSION_NAME:-}}"
RELEASE_CHANNEL="${3:-${APP_RELEASE_CHANNEL:-stable}}"

echo "=========================================================="
echo "  CPPlayer AOT Cache Generator (JDK 25 / JEP 483 & 514)  "
echo "  Destination: $OUTPUT_FILE"
echo "  Version:     ${VERSION_NAME:-<default>}"
echo "  Channel:     $RELEASE_CHANNEL"
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
    # 按脚本实际使用的 java 决定打包参数：JBR 21 没有分代 Shenandoah（JDK 24+）
    # 也没有 Compact Object Headers，硬塞 JDK 25 参数会让生成的启动器和 AOT 训练
    # 在 VM 初始化阶段就失败。
    JAVA_VERSION_OUTPUT="$("$JAVA_BIN" -version 2>&1)"
    JAVA_MAJOR="$(sed -nE '1s/.*version "([0-9]+).*/\1/p' <<<"$JAVA_VERSION_OUTPUT")"
    JAVA_MAJOR="${JAVA_MAJOR:-0}"
    GRADLE_ARGS=(
        ":app:createDistributable"
        "-x" "test"
        "--quiet"
    )
    if (( JAVA_MAJOR >= 25 )); then
        GRADLE_ARGS+=("-Pcp.gc=shenandoah" "-Pcp.compactHeaders=true")
    else
        GRADLE_ARGS+=("-Pcp.gc=g1")
    fi
    if [[ -n "${CP_JBR_HOME:-}" ]]; then
        GRADLE_ARGS+=("-Pcp.jbrHome=$CP_JBR_HOME")
    fi
    if [[ -n "$VERSION_NAME" ]]; then
        GRADLE_ARGS+=("-Papp.versionName=$VERSION_NAME")
    fi
    if [[ -n "$RELEASE_CHANNEL" ]]; then
        GRADLE_ARGS+=("-Papp.releaseChannel=$RELEASE_CHANNEL")
    fi
    (cd "$REPO_ROOT" && ./gradlew "${GRADLE_ARGS[@]}")
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

# ⚠️ AOT 训练必须使用**与生产启动器完全一致**的 VM 参数：CPPlayer.cfg 的
# [JavaOptions] 就是 jpackage 启动器的真值。硬编码一套参数会再次把 Windows 的
# JDK 25 参数带进 Linux 的 JBR 21（`Unknown -XX:ShenandoahGCMode option`），
# 而且训练/运行时参数不一致时 AOT 缓存会被 HotSpot 拒绝复用。
JVM_ARGS=()
if [[ -f "$CFG_FILE" ]]; then
    while IFS= read -r line; do
        case "$line" in
            java-options=*)
                opt="${line#java-options=}"
                opt="${opt//\$APPDIR/$APP_DIR}"
                # 旧镜像里可能已有上次注入的 AOT 参数，带进训练会与本次录制冲突。
                case "$opt" in
                    -XX:AOTCache=*|-XX:AOTCacheOutput=*|-XX:AOTMode=*|-XX:AOTConfiguration=*) continue ;;
                esac
                JVM_ARGS+=("$opt")
                ;;
        esac
    done < "$CFG_FILE"
else
    JVM_ARGS+=(
        "--enable-native-access=ALL-UNNAMED"
        "-Xms64m"
        "-Xmx256m"
    )
fi
JVM_ARGS+=("-Dcp.player.aotWarmup=true")
JVM_ARGS+=("-cp" "$EFFECTIVE_CP")

# 无头 Linux 环境自动采用 xvfb-run 包裹图形上下文
RUN_CMD=("$JAVA_BIN")
if [[ -z "${DISPLAY:-}" ]] && command -v xvfb-run &>/dev/null; then
    echo "  Notice: No DISPLAY detected; wrapping JVM run with xvfb-run..."
    RUN_CMD=(xvfb-run -a "$JAVA_BIN")
fi

# Test for JEP 514 support
if "$JAVA_BIN" -XX:AOTCacheOutput=/dev/null -version &>/dev/null; then
    echo "  Using JEP 514 One-Shot Ahead-of-Time Command-Line Ergonomics..."
    timeout 60s "${RUN_CMD[@]}" "-XX:AOTCacheOutput=$OUTPUT_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
    # JEP 514 在父进程退出后由子进程异步写 .aot 文件；等待最多 30 秒让其完成。
    CONFIG_FILE="${OUTPUT_FILE}.config"
    deadline=$((SECONDS + 30))
    while [[ -f "$CONFIG_FILE" && $SECONDS -lt $deadline ]]; do
        sleep 0.5
    done
else
    echo "  Falling back to JEP 483 two-step workflow..."
    CONFIG_FILE="${OUTPUT_FILE%.*}.aotconf"
    timeout 60s "${RUN_CMD[@]}" "-XX:AOTMode=record" "-XX:AOTConfiguration=$CONFIG_FILE" "${JVM_ARGS[@]}" cp.player.app.MainKt || true
    if [[ -f "$CONFIG_FILE" ]]; then
        timeout 60s "${RUN_CMD[@]}" "-XX:AOTMode=create" "-XX:AOTConfiguration=$CONFIG_FILE" "-XX:AOTCache=$OUTPUT_FILE" "-cp" "$EFFECTIVE_CP" || true
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

    echo "  Validating archive mount with launcher classpath..."
    VERIFY_ARGS=("-XX:AOTCache=$OUTPUT_FILE")
    if [[ -f "$CFG_FILE" ]]; then
        while IFS= read -r line; do
            case "$line" in
                java-options=*)
                    opt="${line#java-options=}"
                    opt="${opt//\$APPDIR/$APP_DIR}"
                    case "$opt" in
                        -XX:AOTCache=*|-XX:AOTCacheOutput=*|-XX:AOTMode=*|-XX:AOTConfiguration=*) continue ;;
                    esac
                    VERIFY_ARGS+=("$opt")
                    ;;
            esac
        done < "$CFG_FILE"
    fi
    VERIFY_ARGS+=("-cp" "$EFFECTIVE_CP" "-version")
    VERIFY_OUT="$("$JAVA_BIN" "${VERIFY_ARGS[@]}" 2>&1 || true)"
    if grep -q "sharing" <<<"$VERIFY_OUT" && ! grep -qi "error" <<<"$VERIFY_OUT"; then
        echo "  AOT Cache successfully verified in sharing mode!"
    else
        echo "  Notice: AOT verification report:"
        echo "$VERIFY_OUT"
    fi
else
    echo "Error: AOT cache was not created." >&2
    exit 1
fi
