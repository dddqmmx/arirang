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
ZYGISK_REAL="$LANDING_DIR/libmod1.so"

arirang_zygisk_hide() {
    [ -d "$ZYGISK_DIR" ] || return 1
    [ -d "$LANDING_DIR" ] || return 1

    # Freshly packaged modules carry the real library at the zygisk path; seed
    # the persistent store from it. Once concealed the zygisk path is a symlink
    # (rebuilt on every boot), so the store is only refreshed on update.
    if [ -f "$ZYGISK_MODULE" ] && [ ! -L "$ZYGISK_MODULE" ]; then
        cp -f "$ZYGISK_MODULE" "$ZYGISK_STORE" 2>/dev/null || return 1
        chmod 0755 "$ZYGISK_STORE" 2>/dev/null
    fi
    [ -f "$ZYGISK_STORE" ] && [ ! -L "$ZYGISK_STORE" ] || return 1

    # /dev/.arirang is tmpfs and is wiped every boot, so always restage a
    # fresh copy. arirang_hook_file is the exec-capable private type; zygote
    # and the Zygisk daemon are granted read/exec over it in sepolicy.rule.
    local tmp_real="$LANDING_DIR/.libmod1.so.$$"
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
