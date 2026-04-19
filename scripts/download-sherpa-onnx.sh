#!/bin/bash
# Download sherpa-onnx AAR for Android
# Usage: ./scripts/download-sherpa-onnx.sh
#
# This downloads the pre-built AAR from GitHub Releases and places it
# in app/libs/sherpa-onnx.aar
#
# The AAR contains:
# - Java/Kotlin API classes (com.k2fsa.sherpa.onnx.*)
# - Native libraries (libsherpa-onnx-jni.so, libonnxruntime.so) for arm64-v8a and armeabi-v7a

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LIBS_DIR="$PROJECT_DIR/app/libs"
AAR_FILE="$LIBS_DIR/sherpa-onnx.aar"

SHERPA_VERSION="1.12.39"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"

mkdir -p "$LIBS_DIR"

# Check if already downloaded
if [ -f "$AAR_FILE" ]; then
    echo "sherpa-onnx AAR already exists: $AAR_FILE"
    echo "Size: $(du -h "$AAR_FILE" | cut -f1)"
    echo "Delete it and re-run to download again."
    exit 0
fi

echo "Downloading sherpa-onnx v${SHERPA_VERSION} AAR (~56MB)..."
echo "URL: $AAR_URL"
echo ""

# Try multiple download methods
if command -v wget &>/dev/null; then
    wget --progress=bar:force -O "$AAR_FILE.tmp" "$AAR_URL" 2>&1
elif command -v curl &>/dev/null; then
    curl -L --progress-bar -o "$AAR_FILE.tmp" "$AAR_URL" 2>&1
else
    echo "Error: Neither wget nor curl found. Please install one of them."
    exit 1
fi

# Verify download
if [ ! -f "$AAR_FILE.tmp" ]; then
    echo "Error: Download failed"
    exit 1
fi

# Move to final location
mv "$AAR_FILE.tmp" "$AAR_FILE"

echo ""
echo "✅ Download complete!"
echo "   File: $AAR_FILE"
echo "   Size: $(du -h "$AAR_FILE" | cut -f1)"
echo ""
echo "You can now build the project with:"
echo "  cd $PROJECT_DIR"
echo "  ./gradlew assembleDebug"
