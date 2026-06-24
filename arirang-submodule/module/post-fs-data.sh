#!/system/bin/sh
# Arirang submodule post-fs-data
#
# Runs in root mount namespace once /data is up, before zygote. We do two
# system-level prep steps here. Nothing in this script touches any third-
# party application process.
#
#   1. Stage the spoofed widevineDrmId from the Arirang app config into a
#      tmpfs path the HAL daemon can read.
#   2. Make our hook .so visible to the vendor linker namespace so that the
#      ptrace-driven dlopen() launched later from service.sh can succeed.
#      KSU 3.0+ does not mount module files into the system / vendor trees
#      by default, so we cannot rely on system/lib64/<so> overlay. Instead
#      we copy the .so into a tmpfs landing dir, label it as vendor_file,
#      and bind-mount it onto an unused, never-loaded vendor lib path.
#
# The bind target is chosen at runtime from a probe list of vendor libs
# that exist on the device but are not loaded by any process and are not
# referenced by ld.config.txt. The chosen path is recorded so service.sh
# can pass it to the injector.

MODDIR="${0%/*}"
INJECTOR="$MODDIR/bin/arirang_injector"

# ----- staging dir on tmpfs -----------------------------------------------
LANDING_DIR="/dev/.arirang"
LANDING_HOOK="$LANDING_DIR/libarirang_drm_hook.so"
LANDING_ID="$LANDING_DIR/widevine_id"
LANDING_BINDPATH="$LANDING_DIR/bind_path"

mkdir -p "$LANDING_DIR"
chmod 0755 "$LANDING_DIR"
chcon u:object_r:vendor_file:s0 "$LANDING_DIR" 2>/dev/null

# ----- copy hook .so into tmpfs and label as vendor -----------------------
SRC_HOOK="$MODDIR/lib/libarirang_drm_hook.so"
if [ -f "$SRC_HOOK" ]; then
    cp -f "$SRC_HOOK" "$LANDING_HOOK"
    chmod 0644 "$LANDING_HOOK"
    chcon u:object_r:vendor_file:s0 "$LANDING_HOOK" 2>/dev/null
else
    log -p e -t arirang_post_fs_data "missing hook .so: $SRC_HOOK"
fi

# ----- pick an unused vendor lib to bind-mount onto -----------------------
# We need a vendor .so path that:
#   * exists on the device,
#   * is not currently mapped into any running process,
#   * is not referenced by ld.config.txt.
# A short, conservative candidate list. None of these are widely used and
# none are referenced by the Widevine HAL or its dependencies.
CANDIDATES="
/vendor/lib64/android.hidl.token@1.0-utils.so
/vendor/lib64/android.hidl.token@1.0.so
/vendor/lib64/android.frameworks.cameraservice.common-V1-ndk.so
/vendor/lib64/android.frameworks.cameraservice.device-V3-ndk.so
/vendor/lib64/libQ6MSFR_manager_stub.so
"

BIND_TARGET=""
for c in $CANDIDATES; do
    [ -f "$c" ] || continue
    # Skip if any running process has this path mapped.
    if grep -hF "$c" /proc/*/maps 2>/dev/null | head -n1 | grep -q .; then
        continue
    fi
    # Skip if ld.config.txt references it (linker preload / namespace).
    base=$(basename "$c")
    if grep -qF "$base" /linkerconfig/ld.config.txt 2>/dev/null; then
        continue
    fi
    BIND_TARGET="$c"
    break
done

if [ -z "$BIND_TARGET" ]; then
    log -p e -t arirang_post_fs_data "no usable vendor bind target found"
    rm -f "$LANDING_BINDPATH"
else
    # Bind-mount our .so over the orphan vendor path. /vendor is shared
    # mount (shared:11) so this propagates to every process.
    if [ -f "$LANDING_HOOK" ]; then
        if mount --bind "$LANDING_HOOK" "$BIND_TARGET" 2>/dev/null; then
            printf '%s' "$BIND_TARGET" > "$LANDING_BINDPATH"
            chmod 0644 "$LANDING_BINDPATH"
            chcon u:object_r:vendor_file:s0 "$LANDING_BINDPATH" 2>/dev/null
            log -p i -t arirang_post_fs_data "hook .so bind-mounted at $BIND_TARGET"
        else
            log -p e -t arirang_post_fs_data "bind mount failed onto $BIND_TARGET"
            rm -f "$LANDING_BINDPATH"
        fi
    fi
fi

# ----- extract widevineDrmId from the app config --------------------------
APP_ID="asia.nana7mi.arirang"
CONFIG_DIR_NAME="arirang-submodule"
CONFIG_FILE_NAME="config.json"

APP_CONFIG_PATH=""
for base in /data/user_de/0 /data/user/0; do
    candidate="$base/$APP_ID/files/$CONFIG_DIR_NAME/$CONFIG_FILE_NAME"
    if [ -f "$candidate" ]; then
        APP_CONFIG_PATH="$candidate"
        break
    fi
done

if [ -z "$APP_CONFIG_PATH" ]; then
    rm -f "$LANDING_ID"
    exit 0
fi

if [ ! -x "$INJECTOR" ]; then
    chmod 0755 "$INJECTOR" 2>/dev/null
fi

get_config_val() {
    "$INJECTOR" config "$APP_CONFIG_PATH" "$1" 2>/dev/null
}

WIDEVINE_ID=$(get_config_val "widevineDrmId")
UNIQUE_ENABLED=$(get_config_val "uniqueIdentifierEnabled")

if [ "$UNIQUE_ENABLED" != "true" ] || [ -z "$WIDEVINE_ID" ]; then
    rm -f "$LANDING_ID"
    exit 0
fi

printf '%s' "$WIDEVINE_ID" > "$LANDING_ID"
chmod 0644 "$LANDING_ID"
chcon u:object_r:vendor_file:s0 "$LANDING_ID" 2>/dev/null

# ----- global system property spoofing via resetprop -----------------------
# Unlike JNI/Zygisk hooks, resetprop modifies the system property area directly,
# affecting all processes (including getprop and native binaries) without
# injecting into them. This aligns with Arirang's system-level design.

ENABLED=$(get_config_val "enabled")
DEVICE_INFO_ENABLED=$(get_config_val "deviceInfoEnabled")

if [ "$ENABLED" = "true" ]; then
    if [ "$DEVICE_INFO_ENABLED" = "true" ]; then
        BRAND=$(get_config_val "buildBrand")
        MANUFACTURER=$(get_config_val "buildManufacturer")
        MODEL=$(get_config_val "buildModel")
        DEVICE=$(get_config_val "buildDevice")
        PRODUCT=$(get_config_val "buildProduct")
        BOARD=$(get_config_val "buildBoard")
        HARDWARE=$(get_config_val "buildHardware")
        FINGERPRINT=$(get_config_val "buildFingerprint")

        [ -n "$BRAND" ] && resetprop ro.product.brand "$BRAND" && resetprop ro.product.vendor.brand "$BRAND"
        [ -n "$MANUFACTURER" ] && resetprop ro.product.manufacturer "$MANUFACTURER" && resetprop ro.product.vendor.manufacturer "$MANUFACTURER"
        [ -n "$MODEL" ] && resetprop ro.product.model "$MODEL" && resetprop ro.product.vendor.model "$MODEL" && resetprop ro.product.system.model "$MODEL" && resetprop ro.product.odm.model "$MODEL"
        [ -n "$DEVICE" ] && resetprop ro.product.device "$DEVICE" && resetprop ro.product.vendor.device "$DEVICE"
        [ -n "$PRODUCT" ] && resetprop ro.product.name "$PRODUCT" && resetprop ro.product.vendor.name "$PRODUCT"
        # Keep the vendor audio HAL working while ro.board.platform is spoofed,
        # WITHOUT exposing any platform-revealing property. When ro.board.platform
        # is faked, libhardware's hw_get_module() can't find
        # audio.primary.<fake>.so and falls back to audio.primary.default.so, which
        # is only a stub -> the volume_listener effect then logs
        # "gain_dep_calibration: service not registered" and the speaker is silent.
        # We bind-mount the REAL platform HAL over that default stub (the same
        # bind-over-existing-file trick used for the DRM hook), so the fallback
        # loads the real driver and its ACDB/gain calibration registers. Apps see
        # only the spoofed ro.board.platform; nothing exposes the real platform
        # (unlike ro.hardware.audio.primary, which apps can read as
        # exported_default_prop). The real platform name comes from the read-only
        # vendor prop file, and the bind persists across audio HAL restarts.
        if [ -n "$BOARD" ]; then
            REAL_PLATFORM=$(grep -m1 '^ro\.board\.platform=' /vendor/build.prop 2>/dev/null | cut -d= -f2)
            if [ -n "$REAL_PLATFORM" ] && [ "$REAL_PLATFORM" != "$BOARD" ]; then
                for hwdir in /vendor/lib64/hw /vendor/lib/hw; do
                    real_so="$hwdir/audio.primary.$REAL_PLATFORM.so"
                    stub_so="$hwdir/audio.primary.default.so"
                    if [ -f "$real_so" ] && [ -f "$stub_so" ]; then
                        if mount --bind "$real_so" "$stub_so"; then
                            log -p i -t arirang_post_fs_data "audio HAL: bound $REAL_PLATFORM over default in $hwdir"
                        else
                            log -p e -t arirang_post_fs_data "audio HAL: bind failed in $hwdir"
                        fi
                    fi
                done
            fi
            resetprop ro.product.board "$BOARD"
            resetprop ro.board.platform "$BOARD"
        fi
        [ -n "$HARDWARE" ] && resetprop ro.hardware "$HARDWARE"
        [ -n "$FINGERPRINT" ] && {
            resetprop ro.build.fingerprint "$FINGERPRINT"
            resetprop ro.vendor.build.fingerprint "$FINGERPRINT"
            resetprop ro.system.build.fingerprint "$FINGERPRINT"
            resetprop ro.odm.build.fingerprint "$FINGERPRINT"
            resetprop ro.bootimage.build.fingerprint "$FINGERPRINT"
        }
    fi

    if [ "$UNIQUE_ENABLED" = "true" ]; then
        SERIAL=$(get_config_val "serial")
        [ -n "$SERIAL" ] && resetprop ro.serialno "$SERIAL" && resetprop ro.boot.serialno "$SERIAL"
    fi

    # Telephony properties
    SIM_NUMERIC=$(get_config_val "gsmSimOperatorNumeric")
    [ -n "$SIM_NUMERIC" ] && resetprop gsm.sim.operator.numeric "$SIM_NUMERIC"
    NET_NUMERIC=$(get_config_val "gsmOperatorNumeric")
    [ -n "$NET_NUMERIC" ] && resetprop gsm.operator.numeric "$NET_NUMERIC"
fi
