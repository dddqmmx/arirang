#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$ROOT_DIR/.." && pwd)"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/23.1.7779620}"
BUILD_DIR="${BUILD_DIR:-/tmp/arirang-zygisk-build}"
STAGE_DIR="${BUILD_DIR}/stage"
OUT_DIR="${PROJECT_DIR}/arirang/dist"
OUT_ZIP="${OUT_DIR}/arirang-submodule.zip"

if [[ ! -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found. Set ANDROID_HOME or ANDROID_NDK_HOME." >&2
  exit 1
fi

cmake -S "$ROOT_DIR" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -G Ninja
cmake --build "$BUILD_DIR"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/zygisk" "$OUT_DIR"
cp "$ROOT_DIR/module/module.prop" "$STAGE_DIR/module.prop"
cp "$BUILD_DIR/libarirang_zygisk.so" "$STAGE_DIR/zygisk/arm64-v8a.so"

(cd "$STAGE_DIR" && zip -qr "$OUT_ZIP" module.prop zygisk)
echo "$OUT_ZIP"
