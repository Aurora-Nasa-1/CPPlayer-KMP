#!/bin/bash
set -e

echo "Building CPPlayer Rust JNI backend for Desktop ($(uname -s))..."

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "$OS" in
    linux)
        TARGET="x86_64-unknown-linux-gnu"
        LIB_NAME="libcp_api.so"
        ;;
    darwin)
        if [ "$ARCH" = "arm64" ]; then
            TARGET="aarch64-apple-darwin"
        else
            TARGET="x86_64-apple-darwin"
        fi
        LIB_NAME="libcp_api.dylib"
        ;;
    mingw*|msys*|cygwin*)
        TARGET="x86_64-pc-windows-gnu"
        LIB_NAME="cp_api.dll"
        ;;
    *)
        echo "Unsupported OS: $OS"
        exit 1
        ;;
esac

echo "Target: $TARGET, Library: $LIB_NAME"

# Build
echo "Running cargo build for $TARGET..."
cargo build --release --features jni --target "$TARGET"

# Prepare package directory
MODULE_DIR="./target/cp_module"
rm -rf "$MODULE_DIR"
mkdir -p "$MODULE_DIR"

# Copy library
cp "target/$TARGET/release/$LIB_NAME" "$MODULE_DIR/"

# Detect platform ABI for manifest
case "$OS-$ARCH" in
    linux-x86_64)  PLATFORM_ABI="x86_64" ;;
    linux-aarch64) PLATFORM_ABI="arm64-v8a" ;;
    darwin-x86_64) PLATFORM_ABI="x86_64" ;;
    darwin-arm64)  PLATFORM_ABI="arm64-v8a" ;;
    mingw*|msys*|cygwin*) PLATFORM_ABI="x86_64" ;;
    *)            PLATFORM_ABI="x86_64" ;;
esac

# Generate manifest
cat <<EOF > "$MODULE_DIR/manifest.json"
{
  "id": "cp_api",
  "name": "NeteaseCloudMusicApi-RS",
  "version": "1.0.0",
  "type": "jni",
  "entryPoint": "$LIB_NAME",
  "supportedAbis": ["$PLATFORM_ABI"],
  "apiMap": {}
}
EOF

# Create zip
ZIP_NAME="cp_api_desktop_${OS}_${ARCH}.zip"
cd "$MODULE_DIR"
zip -r "../$ZIP_NAME" .
cd "$SCRIPT_DIR"

echo ""
echo "Done! Package: target/$ZIP_NAME"
echo "Import this zip into CPPlayer Desktop to use the JNI backend."
