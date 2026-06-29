#!/system/bin/sh
set -eu

MODULE_ZIP="${1:-}"
ROOT_METHOD=""
REBOOT=false

# This script runs on-device via adb shell. It intentionally avoids Bash-only
# syntax because Android /system/bin/sh is mksh/toybox depending on the build.
if [ -z "$MODULE_ZIP" ]; then
  echo "usage: install_module.sh <module.zip> [magisk|ksu|kernelsu|ap|apatch] [--reboot]" >&2
  exit 2
fi
shift || true

while [ "$#" -gt 0 ]; do
  case "$1" in
    --reboot)
      REBOOT=true
      ;;
    magisk|ksu|kernelsu|ap|apatch)
      ROOT_METHOD="$1"
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

if [ ! -f "$MODULE_ZIP" ]; then
  echo "module zip not found: $MODULE_ZIP" >&2
  exit 2
fi

find_cmd() {
  command -v "$1" 2>/dev/null || true
}

install_with_kernelsu() {
  # KernelSU and KernelSU Next have used different ksud locations. Probe all
  # known paths before giving up so Gradle install tasks work across variants.
  KSUD="$(find_cmd ksud)"
  if [ -z "$KSUD" ] && [ -x /data/adb/ksud ]; then
    KSUD=/data/adb/ksud
  fi
  if [ -z "$KSUD" ] && [ -x /data/adb/ksu/bin/ksud ]; then
    KSUD=/data/adb/ksu/bin/ksud
  fi
  if [ -z "$KSUD" ]; then
    return 1
  fi

  echo "installing with KernelSU: $KSUD"
  "$KSUD" module install "$MODULE_ZIP"
  return 0
}

install_with_magisk() {
  MAGISK="$(find_cmd magisk)"
  if [ -z "$MAGISK" ]; then
    return 1
  fi

  echo "installing with Magisk: $MAGISK"
  "$MAGISK" --install-module "$MODULE_ZIP"
  return 0
}

install_with_apatch() {
  if [ ! -x /data/adb/apd ]; then
    return 1
  fi

  echo "installing with APatch: /data/adb/apd"
  /data/adb/apd module install "$MODULE_ZIP"
  return 0
}

case "$ROOT_METHOD" in
  "")
    # Auto-detect in the most common order. A caller can pass an explicit
    # backend when multiple root managers are installed or a device needs a
    # particular implementation.
    if install_with_magisk; then
      :
    elif install_with_kernelsu; then
      :
    elif install_with_apatch; then
      :
    else
      echo "no supported root module installer found" >&2
      exit 127
    fi
    ;;
  magisk)
    install_with_magisk
    ;;
  ksu|kernelsu)
    install_with_kernelsu
    ;;
  ap|apatch)
    install_with_apatch
    ;;
esac

sync

if [ "$REBOOT" = true ]; then
  svc power reboot || reboot
fi
