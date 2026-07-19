#!/bin/bash
set -e

TARGETS=("arm64-v8a" "armeabi-v7a" "x86_64")
echo "Building CPPlayer Rust JNI backend module for ${TARGETS[*]}..."

# Require cargo-ndk
if ! command -v cargo-ndk &> /dev/null; then
    echo "Error: cargo-ndk is not installed. Please install it using: cargo install cargo-ndk"
    exit 1
fi

# Automatically set ANDROID_NDK_HOME if not set, assuming default Android Studio path on Linux
if [ -z "$ANDROID_NDK_HOME" ] && [ -d "$HOME/Android/Sdk/ndk" ]; then
    LATEST_NDK=$(ls -d $HOME/Android/Sdk/ndk/* | sort -V | tail -n 1)
    if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        echo "Set ANDROID_NDK_HOME to $ANDROID_NDK_HOME"
    fi
fi

# Set appropriate rust flags for Android NDK
export RUSTFLAGS="-Clink-arg=-Wl,-z,max-page-size=16384"

# Compile for all target architectures
echo "Running cargo build..."
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ./target/jniLibs build --release --features jni-android

# Prepare packaging directory (multi-arch layout)
MODULE_DIR="./target/cp_module"
rm -rf "$MODULE_DIR"

for ABI in "${TARGETS[@]}"; do
    SO_FILE=$(find "./target/jniLibs/$ABI" -name "*.so" | head -n 1)

    if [ -z "$SO_FILE" ] || [ ! -f "$SO_FILE" ]; then
        echo "Error: Could not find compiled .so file in ./target/jniLibs/$ABI"
        exit 1
    fi

    echo "Found library for $ABI: $SO_FILE"
    mkdir -p "$MODULE_DIR/lib/$ABI"
    cp "$SO_FILE" "$MODULE_DIR/lib/$ABI/libcp_api.so"
done

# Create manifest.json (multi-arch)
cat <<EOF > "$MODULE_DIR/manifest.json"
{
  "id": "cp.provider.rust.default",
  "name": "Default Rust NCM Provider",
  "version": "1.0.0",
  "type": "jni",
  "entryPoint": "libcp_api.so",
  "supportedAbis": ["arm64-v8a", "armeabi-v7a", "x86_64"],
  "apiMap": {}
}
EOF

# Zip the module using Python (no zip command needed)
echo "Packaging module to ./target/cp_rust_provider.zip..."
python3 -c "
import zipfile, os
module_dir = '$MODULE_DIR'
zip_path = './target/cp_rust_provider.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk(module_dir):
        for f in files:
            full = os.path.join(root, f)
            arcname = os.path.relpath(full, module_dir)
            zf.write(full, arcname)
"

echo "✅ Module built successfully: ./target/cp_rust_provider.zip"
echo "Contents:"
python3 -c "
import zipfile
with zipfile.ZipFile('./target/cp_rust_provider.zip', 'r') as zf:
    zf.printdir()
"
echo ""
echo "You can now import this .zip file directly into the CPPlayer App."
