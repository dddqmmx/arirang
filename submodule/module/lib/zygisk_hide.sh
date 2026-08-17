#!/system/bin/sh
# Arirang submodule - zygisk mapping concealment.
#
# Zygisk frameworks load module libraries from
# /data/adb/modules/<name>/zygisk/<abi>.so and re-open that path inside the
# forked process, so the resolved library path is recorded in that process's
# maps. A detector that scans maps (its own process, or zygote where the proc
# hierarchy is world-readable) flags any mapping that still resolves under
# /data/adb/modules as a Zygisk signature (e.g. "Detected Zygisk (N)" counts N
# such mappings).
#
# This script moves the real library onto the root-only staging tmpfs
# (/dev/.arirang, no /data/adb prefix) and leaves only a symlink at the zygisk
# path, so the kernel reports the benign resolved path instead of the canonical
# Zygisk module path. The module must not carry the real library at the zygisk
# path in the shipped zip: the zygisk/arm64-v8a.so entry in the package is a
# regular file so the store can be seeded on first boot.
#
# Nothing here touches any third-party application process.

ZYGISK_DIR="$MODDIR/zygisk"
ZYGISK_MODULE="$ZYGISK_DIR/arm64-v8a.so"
ZYGISK_STORE="$MODDIR/lib/zygisk_impl.so"
# Data-fs path (Session 5 / §22): keep-loaded + this path produced no
# Zygisk card and no Found Injection. Tmpfs /dev/.camera_svc was counted
# as injection (7 cards) on the current detector.
ZYGISK_REAL="/data/system/libhwc_vendor.so"

arirang_zygisk_hide() {
    [ -d "$ZYGISK_DIR" ] || return 1
    [ -d /data/system ] || return 1

    # Freshly packaged modules carry the real library at the zygisk path; seed
    # the persistent store from it. Once concealed the zygisk path is a symlink
    # to the staging copy; refresh the store from that target on every boot so
    # module updates actually take effect (the symlink itself never changes).
    if [ -f "$ZYGISK_MODULE" ] && [ ! -L "$ZYGISK_MODULE" ]; then
        cp -f "$ZYGISK_MODULE" "$ZYGISK_STORE" 2>/dev/null || return 1
        chmod 0755 "$ZYGISK_STORE" 2>/dev/null
    elif [ -L "$ZYGISK_MODULE" ]; then
        local ztarget=$(readlink -f "$ZYGISK_MODULE" 2>/dev/null)
        if [ -n "$ztarget" ] && [ -f "$ztarget" ]; then
            cp -f "$ztarget" "$ZYGISK_STORE" 2>/dev/null || return 1
            chmod 0755 "$ZYGISK_STORE" 2>/dev/null
        fi
    fi
    [ -f "$ZYGISK_STORE" ] && [ ! -L "$ZYGISK_STORE" ] || return 1

    # Restage onto /data/system every boot. system_data_file is already
    # executable by zygote; Session 5 confirmed SELinux permits the load.
    rm -f "$LANDING_DIR/libcamera_hal.so" 2>/dev/null
    local tmp_real="/data/system/.libhwc_vendor.so.$$"
    rm -f "$tmp_real" || return 1
    if ! cp "$ZYGISK_STORE" "$tmp_real" 2>/dev/null ||
        ! chmod 0640 "$tmp_real" ||
        ! chcon u:object_r:arirang_hook_file:s0 "$tmp_real" 2>/dev/null; then
        rm -f "$tmp_real"
        return 1
    fi
    if ! mv -f "$tmp_real" "$ZYGISK_REAL"; then
        rm -f "$tmp_real"
        return 1
    fi

    rm -f "$ZYGISK_MODULE" 2>/dev/null || return 1
    ln -sf "$ZYGISK_REAL" "$ZYGISK_MODULE" 2>/dev/null || return 1

    [ -L "$ZYGISK_MODULE" ] &&
        [ "$(arirang_file_uid "$ZYGISK_MODULE")" = "0" ] &&
        arirang_is_root_regular_file "$ZYGISK_REAL" &&
        arirang_has_hook_context "$ZYGISK_REAL"
}
