# Arirang — tmpfs staging area for hook .so and runtime assets.
# Creates /dev/.arirang and copies the DRM hook library into it
# with correct SELinux labels for vendor accessibility.

arirang_staging_setup() {
    mkdir -p "$LANDING_DIR"
    chmod 0755 "$LANDING_DIR"
    chcon u:object_r:vendor_file:s0 "$LANDING_DIR" 2>/dev/null

    local src_hook="$MODDIR/lib/libarirang_drm_hook.so"
    if [ -f "$src_hook" ]; then
        cp -f "$src_hook" "$LANDING_HOOK"
        chmod 0644 "$LANDING_HOOK"
        chcon u:object_r:vendor_file:s0 "$LANDING_HOOK" 2>/dev/null
    else
        arirang_log e "arirang_post_fs_data" "missing hook .so: $src_hook"
        return 1
    fi
    return 0
}
