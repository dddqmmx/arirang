# Arirang — vendor library bind-mount logic.
# Selects an unused vendor .so and bind-mounts our hook over it,
# making it visible to the vendor linker namespace.

arirang_vendor_bind() {
    # Conservative candidate list: vendor libs that are rarely loaded
    # and never referenced by the Widevine HAL or its dependencies.
    local CANDIDATES="
        /vendor/lib64/android.hidl.token@1.0-utils.so
        /vendor/lib64/android.hidl.token@1.0.so
        /vendor/lib64/android.frameworks.cameraservice.common-V1-ndk.so
        /vendor/lib64/android.frameworks.cameraservice.device-V3-ndk.so
        /vendor/lib64/libQ6MSFR_manager_stub.so
    "

    arirang_vendor_bind_select() {
        for c in $CANDIDATES; do
            [ -f "$c" ] || continue

            # Skip if any running process currently maps this path.
            if grep -hF "$c" /proc/*/maps 2>/dev/null | head -n1 | grep -q .; then
                continue
            fi

            # Skip if ld.config.txt references it for preload or namespace.
            local base="${c##*/}"
            if grep -qF "$base" /linkerconfig/ld.config.txt 2>/dev/null; then
                continue
            fi

            printf '%s' "$c"
            return 0
        done
        return 1
    }

    local bind_target
    bind_target=$(arirang_vendor_bind_select)

    if [ -z "$bind_target" ]; then
        arirang_log e "arirang_post_fs_data" "no usable vendor bind target found"
        rm -f "$LANDING_BINDPATH"
        return 1
    fi

    if [ -f "$LANDING_HOOK" ]; then
        if ! mount --bind "$LANDING_HOOK" "$bind_target" 2>/dev/null; then
            arirang_log e "arirang_post_fs_data" "bind mount failed onto $bind_target"
            rm -f "$LANDING_BINDPATH"
            return 1
        fi

        printf '%s' "$bind_target" > "$LANDING_BINDPATH"
        chmod 0644 "$LANDING_BINDPATH"
        chcon u:object_r:vendor_file:s0 "$LANDING_BINDPATH" 2>/dev/null
        arirang_log i "arirang_post_fs_data" "hook .so bind-mounted at $bind_target"
    fi
    return 0
}
