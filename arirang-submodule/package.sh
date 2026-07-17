#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/23.1.7779620}"
BUILD_DIR_WAS_SET=false
if [[ -n "${BUILD_DIR+x}" ]]; then
  BUILD_DIR_WAS_SET=true
fi
REQUESTED_BUILD_DIR="${BUILD_DIR-}"
OUT_DIR="${ROOT_DIR}/build/outputs"
OUT_ZIP="${OUT_DIR}/arirang-submodule.zip"
BUILD_DIR=""
BUILD_DIR_IS_TEMP=false
STAGE_DIR=""
ARCHIVE_DIR=""

cleanup() {
  if [[ -n "$STAGE_DIR" && -d "$STAGE_DIR" && ! -L "$STAGE_DIR" ]]; then
    rm -rf -- "$STAGE_DIR"
  fi
  if [[ -n "$ARCHIVE_DIR" && -d "$ARCHIVE_DIR" && ! -L "$ARCHIVE_DIR" ]]; then
    rm -rf -- "$ARCHIVE_DIR"
  fi
  if [[ "$BUILD_DIR_IS_TEMP" == true && -n "$BUILD_DIR" && -d "$BUILD_DIR" && ! -L "$BUILD_DIR" ]]; then
    rm -rf -- "$BUILD_DIR"
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in cmake ninja zip unzip mktemp install stat; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required build tool not found: $tool" >&2
    exit 1
  fi
done

if [[ ! -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found. Set ANDROID_HOME or ANDROID_NDK_HOME." >&2
  exit 1
fi

# By default, build in a private directory that cannot be pre-seeded with
# symlinks. An explicitly supplied BUILD_DIR is preserved after the build, but
# must already be owned by this user and not writable by another user.
if [[ "$BUILD_DIR_WAS_SET" == false ]]; then
  BUILD_DIR="$(mktemp -d /tmp/arirang-native-build.XXXXXX)"
  BUILD_DIR_IS_TEMP=true
else
  case "$REQUESTED_BUILD_DIR" in
    ""|/)
      echo "Unsafe BUILD_DIR: $REQUESTED_BUILD_DIR" >&2
      exit 1
      ;;
  esac
  if [[ -e "$REQUESTED_BUILD_DIR" && ( ! -d "$REQUESTED_BUILD_DIR" || -L "$REQUESTED_BUILD_DIR" ) ]]; then
    echo "BUILD_DIR must be a non-symlink directory: $REQUESTED_BUILD_DIR" >&2
    exit 1
  fi
  mkdir -p -- "$REQUESTED_BUILD_DIR"
  if [[ -L "$REQUESTED_BUILD_DIR" || ! -O "$REQUESTED_BUILD_DIR" ]]; then
    echo "BUILD_DIR must be owned by the current user: $REQUESTED_BUILD_DIR" >&2
    exit 1
  fi
  build_mode="$(stat -c '%a' "$REQUESTED_BUILD_DIR")"
  if [[ ! "$build_mode" =~ ^[0-7]{3,4}$ ]] || (( (8#$build_mode & 0022) != 0 )); then
    echo "BUILD_DIR must not be group- or world-writable: $REQUESTED_BUILD_DIR" >&2
    exit 1
  fi
  BUILD_DIR="$(cd "$REQUESTED_BUILD_DIR" && pwd -P)"
fi

cmake -S "$ROOT_DIR" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DCMAKE_BUILD_TYPE=Release \
  -DARIRANG_APPLICATION_ID=asia.nana7mi.arirang \
  -DARIRANG_SUBMODULE_CONFIG_DIR=arirang-submodule \
  -DARIRANG_SUBMODULE_CONFIG_FILE=config.json \
  -G Ninja
cmake --build "$BUILD_DIR"

for artifact in \
  "$BUILD_DIR/libarirang_zygisk.so" \
  "$BUILD_DIR/libarirang_drm_hook.so" \
  "$BUILD_DIR/arirang_injector"; do
  if [[ ! -f "$artifact" || -L "$artifact" ]]; then
    echo "Missing or unsafe native artifact: $artifact" >&2
    exit 1
  fi
done

STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/arirang-module-stage.XXXXXX")"
mkdir -p -- "$STAGE_DIR/lib" "$STAGE_DIR/zygisk" "$STAGE_DIR/bin"

install_module_source() {
  local source="$1" destination="$2" mode="$3"
  if [[ ! -f "$source" || -L "$source" ]]; then
    echo "Unsafe module source: $source" >&2
    return 1
  fi
  install -m "$mode" "$source" "$destination"
}

install_module_source "$ROOT_DIR/module/module.prop" "$STAGE_DIR/module.prop" 0644
install_module_source "$ROOT_DIR/module/post-fs-data.sh" "$STAGE_DIR/post-fs-data.sh" 0755
install_module_source "$ROOT_DIR/module/service.sh" "$STAGE_DIR/service.sh" 0755
install_module_source "$ROOT_DIR/module/sepolicy.rule" "$STAGE_DIR/sepolicy.rule" 0644
for library_script in "$ROOT_DIR"/module/lib/*.sh; do
  [[ -f "$library_script" && ! -L "$library_script" ]] || {
    echo "Unsafe module library script: $library_script" >&2
    exit 1
  }
  install_module_source "$library_script" "$STAGE_DIR/lib/${library_script##*/}" 0644
done
install -m 0644 "$BUILD_DIR/libarirang_zygisk.so" "$STAGE_DIR/zygisk/arm64-v8a.so"
install -m 0644 "$BUILD_DIR/libarirang_drm_hook.so" "$STAGE_DIR/lib/libarirang_drm_hook.so"
install -m 0755 "$BUILD_DIR/arirang_injector" "$STAGE_DIR/bin/arirang_injector"

if [[ -e "$ROOT_DIR/build" && ( ! -d "$ROOT_DIR/build" || -L "$ROOT_DIR/build" ) ]]; then
  echo "Unsafe build output parent: $ROOT_DIR/build" >&2
  exit 1
fi
if [[ -e "$OUT_DIR" && ( ! -d "$OUT_DIR" || -L "$OUT_DIR" ) ]]; then
  echo "Unsafe output directory: $OUT_DIR" >&2
  exit 1
fi
mkdir -p -- "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd -P)"
case "$OUT_DIR" in
  "$ROOT_DIR"/*) ;;
  *)
    echo "Output directory escaped the project: $OUT_DIR" >&2
    exit 1
    ;;
esac
OUT_ZIP="$OUT_DIR/arirang-submodule.zip"
if [[ -e "$OUT_ZIP" && ( ! -f "$OUT_ZIP" || -L "$OUT_ZIP" ) ]]; then
  echo "Unsafe output archive path: $OUT_ZIP" >&2
  exit 1
fi
ARCHIVE_DIR="$(mktemp -d "$OUT_DIR/.arirang-package.XXXXXX")"
TMP_ZIP="$ARCHIVE_DIR/arirang-submodule.zip"

(
  cd "$STAGE_DIR"
  zip -q -r "$TMP_ZIP" \
    module.prop post-fs-data.sh service.sh sepolicy.rule lib zygisk bin
)
unzip -t "$TMP_ZIP" >/dev/null
chmod 0644 "$TMP_ZIP"
mv -f -- "$TMP_ZIP" "$OUT_ZIP"

echo "$OUT_ZIP"
