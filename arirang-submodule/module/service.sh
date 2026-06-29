#!/system/bin/sh
# Arirang submodule late-boot service
#
# Runs after the system has booted. Locates the Widevine DRM HAL daemon
# (vendor.drm-widevine-hal-1-4 / android.hardware.drm@1.4-service.widevine)
# and invokes the bundled arirang_injector to remote-dlopen our hook .so
# inside the daemon's address space.
#
# This script intentionally only operates on system / vendor processes. It
# never touches any third-party application process.

MODDIR="${0%/*}"
INJECTOR="$MODDIR/bin/arirang_injector"
LANDING_BINDPATH="/dev/.arirang/bind_path"
LOG_TAG="arirang_service"

if [ ! -x "$INJECTOR" ]; then
    chmod 0755 "$INJECTOR" 2>/dev/null
fi
if [ ! -x "$INJECTOR" ]; then
    log -p e -t "$LOG_TAG" "injector missing or not executable: $INJECTOR"
    exit 1
fi

# Wait until post-fs-data has recorded the bind-mount target. We give it
# up to ~10 seconds. If it never appears, post-fs-data didn't find a
# usable vendor bind target and there is nothing for us to inject.
i=0
while [ $i -lt 20 ]; do
    if [ -s "$LANDING_BINDPATH" ]; then break; fi
    sleep 0.5
    i=$((i + 1))
done
if [ ! -s "$LANDING_BINDPATH" ]; then
    log -p e -t "$LOG_TAG" "bind path not staged; aborting injection"
    exit 1
fi
BIND_TARGET=$(cat "$LANDING_BINDPATH")

# Wait for the Widevine HAL daemon to come up.
# Device/vendor builds use different service names for the same DRM stack.
# Try newest Widevine HIDL first, then common vendor aliases. Clearkey is kept
# as a development fallback on AOSP-like images where Widevine is absent.
HAL_PID=""
i=0
while [ $i -lt 60 ]; do
    for hal_name in \
        "android.hardware.drm@1.4-service.widevine" \
        "android.hardware.drm@1.3-service.widevine" \
        "vendor.drm-widevine-hal-1-4" \
        "android.hardware.drm@1.4-service.clearkey"; do
        HAL_PID=$(pidof $hal_name 2>/dev/null)
        if [ -n "$HAL_PID" ]; then break 2; fi
    done
    sleep 1
    i=$((i + 1))
done
if [ -z "$HAL_PID" ]; then
    log -p e -t "$LOG_TAG" "Widevine HAL daemon not running; aborting"
    exit 1
fi

log -p i -t "$LOG_TAG" "injecting $BIND_TARGET into pid=$HAL_PID"

# Invoke injector. Output goes to logcat via stderr piped through log.
# BIND_TARGET is the vendor-visible bind-mount path, not the module-private
# /data/adb/modules/.../lib path. The vendor linker namespace may reject the
# module-private path even though root can read it.
"$INJECTOR" "$HAL_PID" "$BIND_TARGET" 2>&1 \
    | while IFS= read -r line; do log -p i -t "$LOG_TAG" "$line"; done

EXIT=${PIPESTATUS:-0}
log -p i -t "$LOG_TAG" "injector exit=$EXIT"
exit $EXIT
