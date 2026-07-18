#!/system/bin/sh
set -eu
umask 077
PATH=/system/bin:/system/xbin
export PATH

MODULE_ZIP="${1:-}"
ROOT_METHOD=""
ROOT_METHOD_SEEN=false
REBOOT=false
INSTALL_DIR=""
INSTALL_DIR_ID=""
STAGED_ZIP=""
STAGED_ZIP_ID=""
STAGED_HASH=""

# This script runs on-device through adb shell and then su. Keep it compatible
# with Android /system/bin/sh (mksh/toybox) and avoid Bash-only features.
usage() {
  echo "usage: install_module.sh <module.zip> [magisk|ksu|kernelsu|ap|apatch] [--reboot]" >&2
}

cleanup_install_dir() {
  CLEANUP_ID=""
  [ -n "$INSTALL_DIR" ] || return 0
  case "$INSTALL_DIR" in
    /data/adb/.arirang-install.*)
      if [ ! -e "$INSTALL_DIR" ] && [ ! -L "$INSTALL_DIR" ]; then
        INSTALL_DIR=""
        INSTALL_DIR_ID=""
        STAGED_ZIP=""
        STAGED_ZIP_ID=""
        STAGED_HASH=""
        return 0
      fi
      [ -d "$INSTALL_DIR" ] && [ ! -L "$INSTALL_DIR" ] || {
        echo "refusing to clean replaced install path: $INSTALL_DIR" >&2
        return 1
      }
      CLEANUP_ID=$(stat -c '%d:%i' "$INSTALL_DIR" 2>/dev/null) || return 1
      [ -n "$INSTALL_DIR_ID" ] && [ "$CLEANUP_ID" = "$INSTALL_DIR_ID" ] || {
        echo "refusing to clean changed install directory: $INSTALL_DIR" >&2
        return 1
      }
      rm -rf "$INSTALL_DIR"
      ;;
    *)
      echo "refusing to clean unexpected install path: $INSTALL_DIR" >&2
      return 1
      ;;
  esac
  INSTALL_DIR=""
  INSTALL_DIR_ID=""
  STAGED_ZIP=""
  STAGED_ZIP_ID=""
  STAGED_HASH=""
}

trap cleanup_install_dir 0
trap 'exit 129' 1
trap 'exit 130' 2
trap 'exit 143' 15

if [ -z "$MODULE_ZIP" ]; then
  usage
  exit 2
fi
shift

while [ "$#" -gt 0 ]; do
  case "$1" in
    --reboot)
      REBOOT=true
      ;;
    magisk|ksu|kernelsu|ap|apatch)
      if [ "$ROOT_METHOD_SEEN" = true ]; then
        echo "root method specified more than once" >&2
        exit 2
      fi
      ROOT_METHOD="$1"
      ROOT_METHOD_SEEN=true
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

case "$MODULE_ZIP" in
  /*) ;;
  *)
    echo "module zip path must be absolute" >&2
    exit 2
    ;;
esac
case "$MODULE_ZIP" in
  *'
'*)
    echo "module zip path contains a newline" >&2
    exit 2
    ;;
esac
if [ ! -f "$MODULE_ZIP" ] || [ -L "$MODULE_ZIP" ]; then
  echo "module zip must be a regular, non-symlink file: $MODULE_ZIP" >&2
  exit 2
fi
if [ "$(/system/bin/id -u 2>/dev/null)" != "0" ]; then
  echo "install_module.sh must run as root" >&2
  exit 1
fi

file_uid() {
  stat -c '%u' "$1" 2>/dev/null
}

mode_is_private_write() {
  MODE=$(stat -c '%A' "$1" 2>/dev/null) || return 1
  case "$MODE" in
    ??????????) ;;
    *) return 1 ;;
  esac
  case "$MODE" in
    ?????w????|????????w?) return 1 ;;
  esac
  return 0
}

file_mode_bits() {
  MODE_BITS=$(stat -c '%a' "$1" 2>/dev/null) || return 1
  case "$MODE_BITS" in
    0[0-7][0-7][0-7]) MODE_BITS=${MODE_BITS#0} ;;
    [0-7][0-7][0-7]) ;;
    *) return 1 ;;
  esac
  printf '%s' "$MODE_BITS"
}

trusted_tool_parents() {
  PARENT_PATH="$1"
  case "$PARENT_PATH" in
    /data/adb/magisk/*)
      PARENT_DIRS="/data/adb /data/adb/magisk"
      ;;
    /data/adb/ksu/bin/*)
      PARENT_DIRS="/data/adb /data/adb/ksu /data/adb/ksu/bin"
      ;;
    /data/adb/ap/bin/*)
      PARENT_DIRS="/data/adb /data/adb/ap /data/adb/ap/bin"
      ;;
    /data/adb/ksud|/data/adb/apd)
      PARENT_DIRS="/data/adb"
      ;;
    *)
      return 0
      ;;
  esac

  for PARENT_DIR in $PARENT_DIRS; do
    [ -d "$PARENT_DIR" ] || return 1
    [ ! -L "$PARENT_DIR" ] || return 1
    [ "$(file_uid "$PARENT_DIR")" = "0" ] || return 1
    mode_is_private_write "$PARENT_DIR" || return 1
  done
  return 0
}

trusted_executable() {
  TOOL_PATH="$1"
  case "$TOOL_PATH" in
    /data/adb/magisk/* | /data/adb/ksu/* | /data/adb/ap/* | \
    /data/adb/ksud | /data/adb/apd | /debug_ramdisk/* | /sbin/* | \
    /system/bin/* | /system/xbin/*)
      ;;
    *) return 1 ;;
  esac

  [ -x "$TOOL_PATH" ] || return 1
  TOOL_REAL=$(readlink -f "$TOOL_PATH" 2>/dev/null) || return 1
  case "$TOOL_REAL" in
    /data/adb/magisk/* | /data/adb/ksu/* | /data/adb/ap/* | \
    /data/adb/ksud | /data/adb/apd | /debug_ramdisk/* | /sbin/* | \
    /system/bin/* | /system/xbin/*)
      ;;
    *) return 1 ;;
  esac
  trusted_tool_parents "$TOOL_PATH" || return 1
  trusted_tool_parents "$TOOL_REAL" || return 1
  [ -f "$TOOL_REAL" ] || return 1
  [ ! -L "$TOOL_REAL" ] || return 1
  [ "$(file_uid "$TOOL_REAL")" = "0" ] || return 1
  mode_is_private_write "$TOOL_REAL" || return 1
  printf '%s' "$TOOL_PATH"
}

sha256_file() {
  HASH_OUTPUT=$("$SHA256SUM" "$1" 2>/dev/null) || return 1
  FILE_HASH=${HASH_OUTPUT%%[[:space:]]*}
  case "$FILE_HASH" in
    *[!0-9A-Fa-f]*) return 1 ;;
  esac
  [ "${#FILE_HASH}" -eq 64 ] || return 1
  printf '%s' "$FILE_HASH"
}

verify_staged_zip() {
  [ -n "$STAGED_ZIP" ] && [ -n "$STAGED_ZIP_ID" ] && [ -n "$STAGED_HASH" ] || return 1
  [ -f "$STAGED_ZIP" ] && [ ! -L "$STAGED_ZIP" ] || return 1
  [ "$(file_uid "$STAGED_ZIP")" = "0" ] || return 1
  [ "$(file_mode_bits "$STAGED_ZIP")" = "600" ] || return 1
  [ "$(stat -c '%d:%i:%s' "$STAGED_ZIP" 2>/dev/null)" = "$STAGED_ZIP_ID" ] || return 1
  [ "$(sha256_file "$STAGED_ZIP")" = "$STAGED_HASH" ]
}

find_trusted_tool() {
  TOOL_NAME="$1"
  shift
  for TOOL_CANDIDATE in "$@"; do
    if TRUSTED_TOOL=$(trusted_executable "$TOOL_CANDIDATE"); then
      printf '%s' "$TRUSTED_TOOL"
      return 0
    fi
  done

  TOOL_CANDIDATE=$(command -v "$TOOL_NAME" 2>/dev/null) || return 1
  trusted_executable "$TOOL_CANDIDATE"
}

prepare_module_snapshot() {
  SOURCE_ID_BEFORE=""
  SOURCE_ID_AFTER=""
  SOURCE_HASH_BEFORE=""
  SOURCE_HASH_AFTER=""
  STAGED_HASH=""
  STAGED_ZIP_ID=""

  [ -d /data/adb ] || {
    echo "/data/adb is unavailable" >&2
    return 1
  }
  [ ! -L /data/adb ] || {
    echo "refusing symlinked /data/adb" >&2
    return 1
  }
  [ "$(file_uid /data/adb)" = "0" ] || {
    echo "/data/adb is not root-owned" >&2
    return 1
  }
  mode_is_private_write /data/adb || {
    echo "/data/adb is writable by a less-privileged user" >&2
    return 1
  }

  SOURCE_SIZE=$(stat -c '%s' "$MODULE_ZIP" 2>/dev/null) || return 1
  case "$SOURCE_SIZE" in
    ''|*[!0-9]*) return 1 ;;
  esac
  if [ "$SOURCE_SIZE" -le 0 ] || [ "$SOURCE_SIZE" -gt 134217728 ]; then
    echo "module zip has an invalid size" >&2
    return 1
  fi

  MKTEMP=$(find_trusted_tool mktemp /system/bin/mktemp /system/xbin/mktemp) || {
    echo "trusted system mktemp not found" >&2
    return 1
  }
  SHA256SUM=$(find_trusted_tool sha256sum /system/bin/sha256sum /system/xbin/sha256sum) || {
    echo "trusted system sha256sum not found" >&2
    return 1
  }
  UNZIP=$(find_trusted_tool unzip /system/bin/unzip /system/xbin/unzip) || {
    echo "trusted system unzip not found" >&2
    return 1
  }

  SOURCE_ID_BEFORE=$(stat -c '%d:%i:%s' "$MODULE_ZIP" 2>/dev/null) || return 1
  SOURCE_HASH_BEFORE=$(sha256_file "$MODULE_ZIP") || {
    echo "failed to hash module zip" >&2
    return 1
  }

  INSTALL_DIR=$("$MKTEMP" -d /data/adb/.arirang-install.XXXXXX) || return 1
  INSTALL_DIR_ID=$(stat -c '%d:%i' "$INSTALL_DIR" 2>/dev/null) || return 1
  chown 0:0 "$INSTALL_DIR" || return 1
  chmod 0700 "$INSTALL_DIR" || return 1
  [ -d "$INSTALL_DIR" ] && [ ! -L "$INSTALL_DIR" ] || return 1
  [ "$(file_uid "$INSTALL_DIR")" = "0" ] || return 1
  [ "$(stat -c '%a' "$INSTALL_DIR" 2>/dev/null)" = "700" ] || return 1
  STAGED_ZIP="$INSTALL_DIR/arirang-submodule.zip"

  cp "$MODULE_ZIP" "$STAGED_ZIP" || return 1
  chown 0:0 "$STAGED_ZIP" || return 1
  chmod 0600 "$STAGED_ZIP" || return 1
  [ -f "$STAGED_ZIP" ] && [ ! -L "$STAGED_ZIP" ] || return 1

  SOURCE_ID_AFTER=$(stat -c '%d:%i:%s' "$MODULE_ZIP" 2>/dev/null) || return 1
  SOURCE_HASH_AFTER=$(sha256_file "$MODULE_ZIP") || return 1
  STAGED_HASH=$(sha256_file "$STAGED_ZIP") || return 1
  if [ "$SOURCE_ID_AFTER" != "$SOURCE_ID_BEFORE" ] ||
    [ "$SOURCE_HASH_AFTER" != "$SOURCE_HASH_BEFORE" ] ||
    [ "$STAGED_HASH" != "$SOURCE_HASH_BEFORE" ]; then
    echo "module zip changed while it was being staged" >&2
    return 1
  fi

  ZIP_SIZE=$(stat -c '%s' "$STAGED_ZIP" 2>/dev/null) || return 1
  case "$ZIP_SIZE" in
    ''|*[!0-9]*) return 1 ;;
  esac
  if [ "$ZIP_SIZE" -ne "$SOURCE_SIZE" ]; then
    echo "module zip has an invalid size" >&2
    return 1
  fi
  STAGED_ZIP_ID=$(stat -c '%d:%i:%s' "$STAGED_ZIP" 2>/dev/null) || return 1
  verify_staged_zip || return 1

  trusted_executable "$UNZIP" >/dev/null || return 1
  "$UNZIP" -t "$STAGED_ZIP" >/dev/null 2>&1 || {
    echo "module zip integrity check failed" >&2
    return 1
  }
  MODULE_PROP_FILE="$INSTALL_DIR/module.prop"
  if ! (ulimit -f 128 || exit 1; "$UNZIP" -p "$STAGED_ZIP" module.prop > "$MODULE_PROP_FILE" 2>/dev/null); then
    echo "module.prop missing from module zip" >&2
    return 1
  fi
  MODULE_PROP_SIZE=$(stat -c '%s' "$MODULE_PROP_FILE" 2>/dev/null) || return 1
  case "$MODULE_PROP_SIZE" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "$MODULE_PROP_SIZE" -gt 0 ] && [ "$MODULE_PROP_SIZE" -le 65536 ] || return 1
  MODULE_PROP=$(cat "$MODULE_PROP_FILE") || return 1
  rm -f "$MODULE_PROP_FILE" || return 1
  MODULE_ID=""
  MODULE_ID_COUNT=0
  while IFS= read -r PROP_LINE || [ -n "$PROP_LINE" ]; do
    case "$PROP_LINE" in
      id=*)
        MODULE_ID="${PROP_LINE#id=}"
        MODULE_ID_COUNT=$((MODULE_ID_COUNT + 1))
        ;;
    esac
  done <<EOF
$MODULE_PROP
EOF
  if [ "$MODULE_ID_COUNT" -ne 1 ] || [ "$MODULE_ID" != "arirang-submodule" ]; then
    echo "module zip is not the Arirang submodule" >&2
    return 1
  fi
}

find_magisk() {
  find_trusted_tool magisk \
    /data/adb/magisk/magisk \
    /debug_ramdisk/magisk \
    /sbin/magisk \
    /system/bin/magisk \
    /system/xbin/magisk
}

find_kernelsu() {
  find_trusted_tool ksud \
    /data/adb/ksud \
    /data/adb/ksu/bin/ksud \
    /system/bin/ksud
}

find_apatch() {
  find_trusted_tool apd /data/adb/apd /data/adb/ap/bin/apd
}

install_with_magisk() {
  MAGISK=$(find_magisk) || return 127
  verify_staged_zip || return 1
  echo "installing with Magisk: $MAGISK"
  if "$MAGISK" --install-module "$STAGED_ZIP"; then
    return 0
  else
    INSTALL_EXIT=$?
    echo "Magisk module installation failed: exit=$INSTALL_EXIT" >&2
    return "$INSTALL_EXIT"
  fi
}

install_with_kernelsu() {
  KSUD=$(find_kernelsu) || return 127
  verify_staged_zip || return 1
  echo "installing with KernelSU: $KSUD"
  if "$KSUD" module install "$STAGED_ZIP"; then
    return 0
  else
    INSTALL_EXIT=$?
    echo "KernelSU module installation failed: exit=$INSTALL_EXIT" >&2
    return "$INSTALL_EXIT"
  fi
}

install_with_apatch() {
  APD=$(find_apatch) || return 127
  verify_staged_zip || return 1
  echo "installing with APatch: $APD"
  if "$APD" module install "$STAGED_ZIP"; then
    return 0
  else
    INSTALL_EXIT=$?
    echo "APatch module installation failed: exit=$INSTALL_EXIT" >&2
    return "$INSTALL_EXIT"
  fi
}

prepare_module_snapshot

if [ -z "$ROOT_METHOD" ]; then
  # Select exactly one available manager. Never fall through to another root
  # manager after a failed/partially-applied installation.
  if find_magisk >/dev/null 2>&1; then
    ROOT_METHOD=magisk
  elif find_kernelsu >/dev/null 2>&1; then
    ROOT_METHOD=ksu
  elif find_apatch >/dev/null 2>&1; then
    ROOT_METHOD=ap
  else
    echo "no supported trusted root module installer found" >&2
    exit 127
  fi
fi

case "$ROOT_METHOD" in
  magisk)
    if install_with_magisk; then
      :
    else
      INSTALL_EXIT=$?
      exit "$INSTALL_EXIT"
    fi
    ;;
  ksu|kernelsu)
    if install_with_kernelsu; then
      :
    else
      INSTALL_EXIT=$?
      exit "$INSTALL_EXIT"
    fi
    ;;
  ap|apatch)
    if install_with_apatch; then
      :
    else
      INSTALL_EXIT=$?
      exit "$INSTALL_EXIT"
    fi
    ;;
esac

sync
cleanup_install_dir
trap - 0

if [ "$REBOOT" = true ]; then
  /system/bin/svc power reboot || /system/bin/reboot
fi
