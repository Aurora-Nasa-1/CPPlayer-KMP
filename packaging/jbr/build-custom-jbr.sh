#!/usr/bin/env bash
# ==============================================================================
# JBR (JetBrains Runtime 21 / 25) Custom Desktop Source Build & Feature Pruner
# ==============================================================================
# Focus: Ultra-minimal desktop footprint, maximum responsiveness, modern GC,
#        virtual threads (Loom), and Java 25 feature flag tuning.
#
# Usage:
#   ./build-custom-jbr.sh [jbr21|jbr25] [output_dir]
#
# Prerequisites (Linux / WSL / Ubuntu):
#   apt-get install -y build-essential autoconf libx11-dev libxext-dev \
#     libxrender-dev libxtst-dev libxt-dev libxrandr-dev libfontconfig1-dev \
#     libasound2-dev libfreetype6-dev
# ==============================================================================

set -euo pipefail

JBR_BRANCH="${1:-jbr21}"
OUTPUT_ROOT="${2:-$(pwd)/dist-jbr}"
BUILD_DIR="$(pwd)/jbr-src"

echo "=========================================================="
echo "  CPPlayer Custom JBR Builder"
echo "  Target Branch: ${JBR_BRANCH} (Java ${JBR_BRANCH#jbr})"
echo "  Output Root  : ${OUTPUT_ROOT}"
echo "=========================================================="

# 1. Clone JBR Source Repository (Shallow Clone for speed & minimal disk)
if [ ! -d "${BUILD_DIR}/.git" ]; then
    echo "[1/4] Cloning JetBrainsRuntime (${JBR_BRANCH})..."
    git clone --depth 1 --branch "${JBR_BRANCH}" https://github.com/JetBrains/JetBrainsRuntime.git "${BUILD_DIR}"
else
    echo "[1/4] Using existing checkout in ${BUILD_DIR}..."
fi

cd "${BUILD_DIR}"

# 2. Configure with Tailored Feature Flags for Desktop
echo "[2/4] Configuring JBR feature flags for ultra-minimal desktop..."

# Feature Flags Decisions:
# - JVM Features:
#   * compiler1, compiler2: Retain C1 + C2 Tiered Compilation for instant startup & peak JIT throughput.
#   * g1gc, zgc, shenandoahgc: Retain modern low-latency & memory-compacting GCs.
#   * link-time-opt, cds: Enable link-time optimization & Class Data Sharing.
#   * STRIPPED: serialgc, parallelgc, epsilongc, jvmci, graal, dtrace, jfr (removes tracing overhead & bulk).
# - JBR Specific:
#   * --disable-jcef: Strip embedded Chromium (~120MB+ size savings, massive RSS savings).
#   * --disable-projector: Strip remote headless projection.
#   * Retain: Native WindowMove & DWM Custom TitleBar enhancements.
# - Debug & Symbols:
#   * --with-debug-level=release
#   * --with-native-debug-symbols=none (no *.pdb or debug symbols generated)
# - Unneeded Subsystems:
#   * --with-cups=no (strip CUPS printing support)
#   * --disable-manpages --disable-docs

JVM_FEATURES="compiler1,compiler2,g1gc,zgc,shenandoahgc,link-time-opt,cds"
if [[ "${JBR_BRANCH}" == *"25"* ]]; then
    JVM_FEATURES="${JVM_FEATURES},compact-headers"
fi

bash configure \
    --with-jvm-variants=server \
    --with-jvm-features="${JVM_FEATURES}" \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --disable-warnings-as-errors \
    --disable-manpages \
    --disable-docs \
    --with-cups=no \
    --enable-headless-only=no \
    --with-extra-cflags="-O3 -flto -fno-semantic-interposition" \
    --with-extra-cxxflags="-O3 -flto -fno-semantic-interposition" \
    --with-extra-ldflags="-Wl,-O1 -Wl,--as-needed -flto" \
    --disable-jcef \
    --disable-projector \
    --with-vendor-name="CPPlayer Custom JBR" \
    --with-vendor-vm-bug-url="https://github.com/CPPlayer/CPPlayer-KMP"

# 3. Build Clean Images
echo "[3/4] Compiling JDK image (make images)..."
make images LOG=info CONF=linux-x86_64-server-release

# 4. Packaging
echo "[4/4] Collecting distribution image..."
IMAGE_DIR="${BUILD_DIR}/build/linux-x86_64-server-release/images/jdk"
mkdir -p "${OUTPUT_ROOT}"
rm -rf "${OUTPUT_ROOT:?}"/*
cp -a "${IMAGE_DIR}"/* "${OUTPUT_ROOT}/"

echo "=========================================================="
echo "Custom JBR build completed successfully!"
echo "Image output: ${OUTPUT_ROOT}"
echo "=========================================================="
