# Arirang - global system property spoofing through a trusted resetprop.

ARIRANG_RESETPROP=""

arirang_resetprop_set() {
    local key="$1" value="$2"
    [ -n "$value" ] || return 0
    if ! arirang_property_name_is_safe "$key"; then
        arirang_log e "arirang_post_fs_data" "invalid property name rejected"
        return 1
    fi
    if ! arirang_config_value_is_safe "$value" 2048; then
        arirang_log w "arirang_post_fs_data" "invalid property value ignored for $key"
        return 1
    fi
    if ! arirang_validate_trusted_tool "$ARIRANG_RESETPROP" >/dev/null; then
        arirang_log e "arirang_post_fs_data" "resetprop executable changed after validation"
        return 1
    fi
    if ! "$ARIRANG_RESETPROP" "$key" "$value"; then
        arirang_log e "arirang_post_fs_data" "resetprop failed for $key"
        return 1
    fi
    return 0
}

arirang_resetprop_product_pair() {
    local suffix="$1" value="$2" status=0
    arirang_resetprop_set "ro.product.$suffix" "$value" || status=1
    arirang_resetprop_set "ro.product.vendor.$suffix" "$value" || status=1
    return "$status"
}

arirang_property_name_is_safe() {
    local key="$1"
    [ "${#key}" -le 96 ] || return 1
    case "$key" in
        ''|*[!A-Za-z0-9._-]*) return 1 ;;
    esac
    return 0
}

arirang_property_token_is_safe() {
    local value="$1"
    arirang_config_value_is_safe "$value" 256 || return 1
    case "$value" in
        ''|*[!A-Za-z0-9._:-]*) return 1 ;;
    esac
    return 0
}

arirang_operator_numeric_is_safe() {
    local value="$1"
    arirang_config_value_is_safe "$value" 128 || return 1
    case "$value" in
        ''|*[!0-9,]*) return 1 ;;
    esac
    return 0
}

arirang_fixup_audio_hal() {
    local spoofed_board="$1" real_platform hwdir real_so module_type default_so
    local prop_line build_prop source_identity target_identity current_identity
    real_platform=""
    build_prop=$(arirang_resolve_trusted_vendor_file /vendor/build.prop) || build_prop=""
    if [ -n "$build_prop" ]; then
        while IFS= read -r prop_line; do
            case "$prop_line" in
                ro.board.platform=*)
                    real_platform=${prop_line#ro.board.platform=}
                    break
                    ;;
            esac
        done < "$build_prop"
    fi
    arirang_property_token_is_safe "$real_platform" || return 0
    [ "$real_platform" = "$spoofed_board" ] && return 0

    for hwdir in /vendor/lib64/hw /vendor/lib/hw; do
        arirang_is_root_directory "$hwdir" || continue
        for real_so in "$hwdir"/audio.*."$real_platform".so; do
            arirang_is_root_regular_file "$real_so" || continue

            module_type="${real_so##*/audio.}"
            module_type="${module_type%%.$real_platform.so}"
            arirang_property_token_is_safe "$module_type" || continue
            default_so="$hwdir/audio.${module_type}.default.so"
            arirang_is_root_regular_file "$default_so" || continue
            arirang_is_mountpoint "$default_so" && continue

            source_identity=$(stat -c '%d:%i' "$real_so" 2>/dev/null) || continue
            target_identity=$(stat -c '%d:%i' "$default_so" 2>/dev/null) || continue
            arirang_is_root_regular_file "$real_so" || continue
            arirang_is_root_regular_file "$default_so" || continue
            arirang_is_mountpoint "$default_so" && continue
            current_identity=$(stat -c '%d:%i' "$real_so" 2>/dev/null) || continue
            [ "$current_identity" = "$source_identity" ] || continue
            current_identity=$(stat -c '%d:%i' "$default_so" 2>/dev/null) || continue
            [ "$current_identity" = "$target_identity" ] || continue

            if ! mount --bind "$real_so" "$default_so" 2>/dev/null; then
                arirang_log e "arirang_post_fs_data" "audio HAL mount failed for $real_so"
                continue
            fi
            if ! arirang_is_mountpoint "$default_so" ||
                ! arirang_same_file "$real_so" "$default_so"; then
                umount "$default_so" 2>/dev/null || :
                arirang_log e "arirang_post_fs_data" "audio HAL mount verification failed"
                continue
            fi
            arirang_log i "arirang_post_fs_data" "audio HAL bind mounted $real_so -> $default_so"
        done
    done
}

arirang_resetprop_device() {
    local brand manufacturer model device product board hardware fingerprint
    local part prop status=0
    brand=$(get_safe_config_val "buildBrand" 256) || brand=""
    manufacturer=$(get_safe_config_val "buildManufacturer" 256) || manufacturer=""
    model=$(get_safe_config_val "buildModel" 256) || model=""
    device=$(get_safe_config_val "buildDevice" 256) || device=""
    product=$(get_safe_config_val "buildProduct" 256) || product=""
    board=$(get_safe_config_val "buildBoard" 256) || board=""
    hardware=$(get_safe_config_val "buildHardware" 256) || hardware=""
    fingerprint=$(get_safe_config_val "buildFingerprint" 2048) || fingerprint=""

    [ -z "$brand" ] || arirang_resetprop_product_pair brand "$brand" || status=1
    [ -z "$manufacturer" ] ||
        arirang_resetprop_product_pair manufacturer "$manufacturer" || status=1
    if [ -n "$model" ]; then
        arirang_resetprop_product_pair model "$model" || status=1
        arirang_resetprop_set ro.product.system.model "$model" || status=1
        arirang_resetprop_set ro.product.odm.model "$model" || status=1
    fi
    [ -z "$device" ] || arirang_resetprop_product_pair device "$device" || status=1
    [ -z "$product" ] || arirang_resetprop_product_pair name "$product" || status=1

    if [ -n "$hardware" ]; then
        if arirang_property_token_is_safe "$hardware"; then
            arirang_resetprop_set ro.hardware "$hardware" || status=1
        else
            arirang_log w "arirang_post_fs_data" "invalid buildHardware ignored"
        fi
    fi

    # Spoofing ro.board.platform changes audio HAL discovery. Bind only
    # verified immutable files and verify the resulting mount before changing
    # any platform properties.
    if [ -n "$board" ]; then
        if arirang_property_token_is_safe "$board"; then
            arirang_fixup_audio_hal "$board"
            arirang_resetprop_set ro.product.board "$board" || status=1
            arirang_resetprop_set ro.board.platform "$board" || status=1
            arirang_resetprop_set ro.hardware.audio.primary "$board" || status=1
        else
            arirang_log w "arirang_post_fs_data" "invalid buildBoard ignored"
        fi
    fi

    if [ -n "$fingerprint" ]; then
        for part in "" vendor system odm bootimage; do
            prop="ro${part:+.${part}}.build.fingerprint"
            arirang_resetprop_set "$prop" "$fingerprint" || status=1
        done
    fi
    return "$status"
}

arirang_resetprop_identity() {
    local serial status=0
    serial=$(get_safe_config_val "serial" 128) || serial=""
    [ -n "$serial" ] || return 0
    if ! arirang_property_token_is_safe "$serial"; then
        arirang_log w "arirang_post_fs_data" "invalid serial ignored"
        return 0
    fi
    arirang_resetprop_set ro.serialno "$serial" || status=1
    arirang_resetprop_set ro.boot.serialno "$serial" || status=1
    return "$status"
}

arirang_resetprop_telephony() {
    local sim_numeric net_numeric status=0
    sim_numeric=$(get_safe_config_val "gsmSimOperatorNumeric" 128) || sim_numeric=""
    net_numeric=$(get_safe_config_val "gsmOperatorNumeric" 128) || net_numeric=""

    if [ -n "$sim_numeric" ]; then
        if arirang_operator_numeric_is_safe "$sim_numeric"; then
            arirang_resetprop_set gsm.sim.operator.numeric "$sim_numeric" || status=1
        else
            arirang_log w "arirang_post_fs_data" "invalid SIM operator numeric ignored"
        fi
    fi
    if [ -n "$net_numeric" ]; then
        if arirang_operator_numeric_is_safe "$net_numeric"; then
            arirang_resetprop_set gsm.operator.numeric "$net_numeric" || status=1
        else
            arirang_log w "arirang_post_fs_data" "invalid network operator numeric ignored"
        fi
    fi
    return "$status"
}

arirang_resetprop_apply() {
    local enabled device_info unique_enabled status=0
    enabled=$(get_safe_config_val "enabled" 5) || enabled=""
    [ "$enabled" = "true" ] || return 0

    ARIRANG_RESETPROP=$(arirang_find_trusted_tool resetprop) || ARIRANG_RESETPROP=""
    if [ -z "$ARIRANG_RESETPROP" ]; then
        arirang_log e "arirang_post_fs_data" "trusted resetprop executable not found"
        return 1
    fi

    device_info=$(get_safe_config_val "deviceInfoEnabled" 5) || device_info=""
    unique_enabled=$(get_safe_config_val "uniqueIdentifierEnabled" 5) || unique_enabled=""

    [ "$device_info" != "true" ] || arirang_resetprop_device || status=1
    [ "$unique_enabled" != "true" ] || arirang_resetprop_identity || status=1
    arirang_resetprop_telephony || status=1
    return "$status"
}
