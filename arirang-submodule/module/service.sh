#!/system/bin/sh
# Arirang submodule — late-boot service.
# Runs after the system has booted. Locates the Widevine DRM HAL daemon
# and invokes the bundled arirang_injector to remote-dlopen our hook .so
# inside the daemon's address space.
# Never touches any third-party application process.

MODDIR="${0%/*}"
. "$MODDIR/lib/common.sh"

LOG_TAG="arirang_service"

if [ ! -x "$INJECTOR" ]; then
    log -p e -t "$LOG_TAG" "injector missing or not executable: $INJECTOR"
    exit 1
fi

# Wait for post-fs-data to record the bind-mount target. Timeout ~10s.
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

# Wait for the Widevine HAL daemon. Timeout ~60s.
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

"$INJECTOR" "$HAL_PID" "$BIND_TARGET" 2>&1 \
    | while IFS= read -r line; do log -p i -t "$LOG_TAG" "$line"; done

EXIT=${PIPESTATUS:-0}
log -p i -t "$LOG_TAG" "injector exit=$EXIT"
exit $EXIT
