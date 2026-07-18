# Arirang - validate and stage the spoofed Widevine device identifier.

arirang_widevine_id_is_valid() {
    local value="$1" length
    arirang_config_value_is_safe "$value" 256 || return 1
    case "$value" in
        ''|*[!0-9A-Fa-f]*) return 1 ;;
    esac

    # The accepted alphabet is ASCII-only, so shell character length and byte
    # length are identical and no pipeline exit status can be lost here.
    length=${#value}
    [ $((length % 2)) -eq 0 ]
}

arirang_widevine_write_id() {
    local widevine_id="$1"
    local tmp_id="$LANDING_DIR/.widevine_id.$$"
    local landing_gid

    rm -f "$tmp_id" || return 1
    printf '%s' "$widevine_id" > "$tmp_id" || return 1
    if ! chown 0:mediadrm "$tmp_id" 2>/dev/null ||
        ! chmod 0640 "$tmp_id" ||
        ! chcon u:object_r:arirang_data_file:s0 "$tmp_id" 2>/dev/null; then
        rm -f "$tmp_id"
        return 1
    fi

    if ! mv -f "$tmp_id" "$LANDING_ID"; then
        rm -f "$tmp_id"
        return 1
    fi
    landing_gid=$(arirang_file_gid "$LANDING_DIR") || {
        rm -f "$LANDING_ID"
        return 1
    }
    if ! arirang_is_root_file_with_mode "$LANDING_ID" 640 ||
        ! arirang_has_data_context "$LANDING_ID" ||
        [ "$(arirang_file_gid "$LANDING_ID")" != "$landing_gid" ]; then
        rm -f "$LANDING_ID"
        return 1
    fi
    return 0
}

arirang_widevine_extract() {
    local widevine_id enabled_flag unique_flag

    # Remove first so missing, disabled, malformed, or unreadable config can
    # never leave a previous identifier active.
    rm -f "$LANDING_ID" || return 1
    if [ -z "$ARIRANG_CONFIG_PATH" ]; then
        arirang_log e "arirang_post_fs_data" "app config not found; skipping widevine"
        return 0
    fi

    enabled_flag=$(get_safe_config_val "enabled" 5) || enabled_flag=""
    unique_flag=$(get_safe_config_val "uniqueIdentifierEnabled" 5) || unique_flag=""
    [ "$enabled_flag" = "true" ] || return 0
    [ "$unique_flag" = "true" ] || return 0

    widevine_id=$(get_safe_config_val "widevineDrmId" 256) || widevine_id=""
    if ! arirang_widevine_id_is_valid "$widevine_id"; then
        [ -z "$widevine_id" ] ||
            arirang_log w "arirang_post_fs_data" "invalid widevine ID ignored"
        return 0
    fi

    if ! arirang_widevine_write_id "$widevine_id"; then
        rm -f "$LANDING_ID"
        arirang_log e "arirang_post_fs_data" "failed to secure widevine ID staging"
        return 1
    fi
    arirang_log i "arirang_post_fs_data" "widevine ID staged"
    return 0
}
