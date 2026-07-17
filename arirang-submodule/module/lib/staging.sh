# Arirang - protected tmpfs staging for the DRM hook and runtime assets.

arirang_staging_prepare_dir() {
    if [ -L "$LANDING_DIR" ] || { [ -e "$LANDING_DIR" ] && [ ! -d "$LANDING_DIR" ]; }; then
        arirang_log e "arirang_post_fs_data" "unsafe staging path: $LANDING_DIR"
        return 1
    fi

    if [ ! -d "$LANDING_DIR" ]; then
        mkdir "$LANDING_DIR" || return 1
    fi

    # widevine_id is sensitive. Only root and the mediadrm principal may
    # traverse this directory; service.sh remains root and bind_path is 0600.
    chown 0:mediadrm "$LANDING_DIR" 2>/dev/null || {
        arirang_log e "arirang_post_fs_data" "failed to set mediadrm staging ownership"
        return 1
    }
    chmod 0750 "$LANDING_DIR" || return 1
    chcon u:object_r:arirang_data_file:s0 "$LANDING_DIR" 2>/dev/null || {
        arirang_log e "arirang_post_fs_data" "failed to label staging directory"
        return 1
    }

    arirang_is_root_dir_with_mode "$LANDING_DIR" 750 &&
        arirang_has_data_context "$LANDING_DIR"
}

arirang_staging_cleanup_previous_bind() {
    local old_target
    [ -e "$LANDING_BINDPATH" ] || [ -L "$LANDING_BINDPATH" ] || return 0

    if ! arirang_is_root_file_with_mode "$LANDING_BINDPATH" 600 ||
        ! arirang_has_data_context "$LANDING_BINDPATH"; then
        arirang_log e "arirang_post_fs_data" "unsafe previous bind-path record"
        return 1
    fi

    old_target=$(cat "$LANDING_BINDPATH" 2>/dev/null) || return 1
    arirang_config_value_is_safe "$old_target" 256 || return 1
    arirang_vendor_target_allowed "$old_target" || return 1

    if arirang_is_mountpoint "$old_target"; then
        if ! arirang_is_root_file_with_mode "$LANDING_HOOK" 640 ||
            ! arirang_has_hook_context "$LANDING_HOOK" ||
            ! arirang_same_file "$LANDING_HOOK" "$old_target"; then
            arirang_log e "arirang_post_fs_data" "refusing to unmount unverified previous bind target"
            return 1
        fi
        umount "$old_target" 2>/dev/null || {
            arirang_log e "arirang_post_fs_data" "failed to remove previous hook bind mount"
            return 1
        }
    fi

    rm -f "$LANDING_BINDPATH" || return 1
    return 0
}

arirang_staging_cleanup_orphan_binds() {
    local candidate
    for candidate in $ARIRANG_VENDOR_CANDIDATES; do
        arirang_vendor_target_allowed "$candidate" || continue
        arirang_is_mountpoint "$candidate" || continue

        # A crash between mount(2) and publishing bind_path can leave an
        # unrecorded mount. The dedicated label proves it is Arirang-owned;
        # never disturb an unrelated mount on the same allowlisted path.
        arirang_is_root_file_with_mode "$candidate" 640 || continue
        arirang_has_hook_context "$candidate" || continue
        if [ -e "$LANDING_HOOK" ] || [ -L "$LANDING_HOOK" ]; then
            arirang_is_root_file_with_mode "$LANDING_HOOK" 640 || return 1
            arirang_has_hook_context "$LANDING_HOOK" || return 1
            arirang_same_file "$LANDING_HOOK" "$candidate" || return 1
        fi
        umount "$candidate" 2>/dev/null || return 1
    done
    return 0
}

arirang_staging_copy_hook() {
    local src_hook="$MODDIR/lib/libarirang_drm_hook.so"
    local tmp_hook="$LANDING_DIR/.libarirang_drm_hook.so.$$"
    local landing_gid

    if ! arirang_is_root_regular_file "$src_hook"; then
        arirang_log e "arirang_post_fs_data" "hook library failed ownership or file checks"
        return 1
    fi

    rm -f "$tmp_hook" || return 1
    if ! cp "$src_hook" "$tmp_hook"; then
        arirang_log e "arirang_post_fs_data" "failed to stage hook library"
        return 1
    fi

    if ! chown 0:mediadrm "$tmp_hook" 2>/dev/null ||
        ! chmod 0640 "$tmp_hook" ||
        ! chcon u:object_r:arirang_hook_file:s0 "$tmp_hook" 2>/dev/null; then
        rm -f "$tmp_hook"
        arirang_log e "arirang_post_fs_data" "failed to secure staged hook library"
        return 1
    fi

    if ! mv -f "$tmp_hook" "$LANDING_HOOK"; then
        rm -f "$tmp_hook"
        return 1
    fi
    landing_gid=$(arirang_file_gid "$LANDING_DIR") || {
        rm -f "$LANDING_HOOK"
        return 1
    }
    if ! arirang_is_root_file_with_mode "$LANDING_HOOK" 640 ||
        ! arirang_has_hook_context "$LANDING_HOOK" ||
        [ "$(arirang_file_gid "$LANDING_HOOK")" != "$landing_gid" ]; then
        rm -f "$LANDING_HOOK"
        return 1
    fi
    return 0
}

arirang_staging_setup() {
    arirang_staging_prepare_dir || return 1
    arirang_staging_cleanup_previous_bind || return 1
    arirang_staging_cleanup_orphan_binds || return 1

    # Old sensitive data must not survive a failed or disabled new setup.
    rm -f "$LANDING_ID" "$LANDING_HOOK" || return 1
    arirang_staging_copy_hook || return 1
    return 0
}
