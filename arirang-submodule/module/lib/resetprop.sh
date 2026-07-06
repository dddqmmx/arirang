# Arirang — global system property spoofing via resetprop.
# Unlike JNI/Zygisk hooks, resetprop modifies the system property area
# directly and affects all processes without injecting into them.

arirang_resetprop_device() {
    local brand manufacturer model device product board hardware fingerprint
    brand=$(get_config_val "buildBrand")
    manufacturer=$(get_config_val "buildManufacturer")
    model=$(get_config_val "buildModel")
    device=$(get_config_val "buildDevice")
    product=$(get_config_val "buildProduct")
    board=$(get_config_val "buildBoard")
    hardware=$(get_config_val "buildHardware")
    fingerprint=$(get_config_val "buildFingerprint")

    # Helper: set both ro.product.X and ro.product.vendor.X
    setprop_dual() {
        local key="$1" val="$2"
        [ -n "$val" ] && resetprop "$key" "$val" && resetprop "${key/vendor./vendor.}" "$val"
    }

    [ -n "$brand" ] && { resetprop ro.product.brand "$brand"; resetprop ro.product.vendor.brand "$brand"; }
    [ -n "$manufacturer" ] && { resetprop ro.product.manufacturer "$manufacturer"; resetprop ro.product.vendor.manufacturer "$manufacturer"; }
    [ -n "$model" ] && {
        resetprop ro.product.model "$model"
        resetprop ro.product.vendor.model "$model"
        resetprop ro.product.system.model "$model"
        resetprop ro.product.odm.model "$model"
    }
    [ -n "$device" ] && { resetprop ro.product.device "$device"; resetprop ro.product.vendor.device "$device"; }
    [ -n "$product" ] && { resetprop ro.product.name "$product"; resetprop ro.product.vendor.name "$product"; }
    [ -n "$hardware" ] && resetprop ro.hardware "$hardware"

    # Audio HAL compatibility: when spoofing ro.board.platform, the vendor
    # audio HAL (audio.primary.<platform>.so) will fail to load because the
    # new platform name doesn't match any real .so. We bind-mount the real
    # platform .so over audio.primary.default.so so the fallback path works.
    arirang_fixup_audio_hal() {
        local real_platform
        real_platform=$(grep -m1 '^ro\.board\.platform=' /vendor/build.prop 2>/dev/null | cut -d= -f2)
        [ -z "$real_platform" ] && return 0
        [ "$real_platform" = "$board" ] && return 0

        for hwdir in /vendor/lib64/hw /vendor/lib/hw; do
            [ -d "$hwdir" ] || continue
            for real_so in "$hwdir"/audio.*."$real_platform".so; do
                [ -e "$real_so" ] || continue
                local module_type="${real_so##*/audio.}"
                module_type="${module_type%%.$real_platform.so}"
                local default_so="$hwdir/audio.${module_type}.default.so"
                if [ -e "$default_so" ]; then
                    if mount --bind "$real_so" "$default_so"; then
                        arirang_log i "arirang_post_fs_data" "audio HAL: bind mounted $real_so -> $default_so"
                    else
                        arirang_log e "arirang_post_fs_data" "audio HAL: mount failed for $real_so"
                    fi
                fi
            done
        done
    }

    if [ -n "$board" ]; then
        arirang_fixup_audio_hal
        resetprop ro.product.board "$board"
        resetprop ro.board.platform "$board"
        resetprop ro.hardware.audio.primary "$board"
    fi

    [ -n "$fingerprint" ] && {
        for part in "" vendor system odm bootimage; do
            local prop="ro${part:+.${part}}.build.fingerprint"
            resetprop "$prop" "$fingerprint"
        done
    }
}

arirang_resetprop_identity() {
    local serial
    serial=$(get_config_val "serial")
    [ -n "$serial" ] && { resetprop ro.serialno "$serial"; resetprop ro.boot.serialno "$serial"; }
}

arirang_resetprop_telephony() {
    local sim_numeric net_numeric
    sim_numeric=$(get_config_val "gsmSimOperatorNumeric")
    net_numeric=$(get_config_val "gsmOperatorNumeric")
    [ -n "$sim_numeric" ] && resetprop gsm.sim.operator.numeric "$sim_numeric"
    [ -n "$net_numeric" ] && resetprop gsm.operator.numeric "$net_numeric"
}

arirang_resetprop_apply() {
    local enabled device_info unique_enabled
    enabled=$(get_config_val "enabled")
    [ "$enabled" != "true" ] && return 0

    device_info=$(get_config_val "deviceInfoEnabled")
    unique_enabled=$(get_config_val "uniqueIdentifierEnabled")

    [ "$device_info" = "true" ] && arirang_resetprop_device
    [ "$unique_enabled" = "true" ] && arirang_resetprop_identity
    arirang_resetprop_telephony
}
