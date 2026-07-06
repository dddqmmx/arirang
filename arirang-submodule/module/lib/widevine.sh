# Arirang — widevineDrmId extraction and staging.
# Reads the spoofed deviceUniqueId from the app config and writes
# it to tmpfs for the DRM hook daemon to consume.

arirang_widevine_extract() {
    if [ -z "$ARIRANG_CONFIG_PATH" ]; then
        arirang_log e "arirang_post_fs_data" "app config not found; skipping widevine"
        rm -f "$LANDING_ID"
        return 0
    fi

    local widevine_id enabled_flag
    widevine_id=$(get_config_val "widevineDrmId")
    enabled_flag=$(get_config_val "uniqueIdentifierEnabled")

    if [ "$enabled_flag" != "true" ] || [ -z "$widevine_id" ]; then
        rm -f "$LANDING_ID"
        return 0
    fi

    printf '%s' "$widevine_id" > "$LANDING_ID"
    chmod 0644 "$LANDING_ID"
    chcon u:object_r:vendor_file:s0 "$LANDING_ID" 2>/dev/null
    arirang_log i "arirang_post_fs_data" "widevine ID staged"
    return 0
}
