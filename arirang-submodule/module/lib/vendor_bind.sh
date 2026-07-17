# Arirang - vendor library bind-mount selection and recording.

# /proc/<pid>/maps lines are "addr-addr perms offset dev inode pathname";
# strip the first five whitespace-delimited fields and treat the remainder as
# the pathname, mirroring service.sh's arirang_maps_path_from_line. A plain
# substring grep would also match a candidate path that is a strict prefix of
# an unrelated mapped file (e.g. "foo.so" inside "foo.so.bak"), so this
# extracts the exact field instead of testing for a substring.
arirang_maps_exact_path_from_line() {
    local remainder="$1" previous field_index=0
    while [ "$field_index" -lt 5 ]; do
        previous="$remainder"
        remainder=${remainder#* }
        [ "$remainder" != "$previous" ] || return 1
        while [ "${remainder# }" != "$remainder" ]; do
            remainder=${remainder# }
        done
        field_index=$((field_index + 1))
    done
    case "$remainder" in
        ''|*' '*) return 1 ;;
    esac
    printf '%s' "$remainder"
}

arirang_vendor_target_is_mapped() {
    local candidate="$1" maps proc_dir scanned=false map_line map_path
    local IFS="$ARIRANG_WORD_IFS"
    for maps in /proc/[0-9]*/maps; do
        proc_dir="${maps%/maps}"
        [ -e "$maps" ] || continue
        [ -r "$maps" ] || return 0
        scanned=true
        while IFS= read -r map_line; do
            map_path=$(arirang_maps_exact_path_from_line "$map_line") || continue
            [ "$map_path" = "$candidate" ] && return 0
        done < "$maps"
    done

    # If procfs could not be inspected, selecting a library is unsafe.
    [ "$scanned" = true ] || return 0
    return 1
}

# ld.config.txt sections list library basenames as exact tokens delimited by
# ':', ',', '=' or whitespace (e.g. "namespace.default.links =
# libc.so:libm.so"). A substring grep would also match a basename that is a
# strict substring of an unrelated token (e.g. "libfoo.so" inside
# "libfoobar.so"), so this splits each line into tokens and compares exactly.
arirang_config_line_has_exact_token() {
    local line="$1" token="$2" field
    local IFS=': 	,='
    for field in $line; do
        [ "$field" = "$token" ] && return 0
    done
    return 1
}

arirang_vendor_target_is_referenced() {
    local candidate="$1" base config scanned=false config_line
    base="${candidate##*/}"

    for config in \
        /linkerconfig/ld.config.txt \
        /system/etc/ld.config*.txt \
        /vendor/etc/ld.config*.txt; do
        [ -e "$config" ] || continue
        [ -r "$config" ] || return 0
        scanned=true
        while IFS= read -r config_line; do
            arirang_config_line_has_exact_token "$config_line" "$base" && return 0
        done < "$config"
    done
    [ "$scanned" = true ] || return 0
    return 1
}

arirang_vendor_bind_select() {
    local candidate
    for candidate in $ARIRANG_VENDOR_CANDIDATES; do
        arirang_vendor_target_allowed "$candidate" || continue
        arirang_is_root_regular_file "$candidate" || continue
        arirang_is_mountpoint "$candidate" && continue
        arirang_vendor_target_is_mapped "$candidate" && continue
        arirang_vendor_target_is_referenced "$candidate" && continue

        printf '%s' "$candidate"
        return 0
    done
    return 1
}

arirang_vendor_write_bind_record() {
    local bind_target="$1"
    local tmp_record="$LANDING_DIR/.bind_path.$$"

    arirang_vendor_target_allowed "$bind_target" || return 1
    rm -f "$tmp_record" || return 1
    printf '%s' "$bind_target" > "$tmp_record" || return 1

    if ! chown 0:0 "$tmp_record" 2>/dev/null ||
        ! chmod 0600 "$tmp_record" ||
        ! chcon u:object_r:arirang_data_file:s0 "$tmp_record" 2>/dev/null; then
        rm -f "$tmp_record"
        return 1
    fi

    if ! mv -f "$tmp_record" "$LANDING_BINDPATH"; then
        rm -f "$tmp_record"
        return 1
    fi
    arirang_is_root_file_with_mode "$LANDING_BINDPATH" 600 &&
        arirang_has_data_context "$LANDING_BINDPATH"
}

arirang_vendor_bind() {
    local bind_target target_identity current_identity

    if ! arirang_is_root_file_with_mode "$LANDING_HOOK" 640 ||
        ! arirang_has_hook_context "$LANDING_HOOK"; then
        arirang_log e "arirang_post_fs_data" "staged hook failed ownership or file checks"
        return 1
    fi

    bind_target=$(arirang_vendor_bind_select) || bind_target=""
    if [ -z "$bind_target" ]; then
        arirang_log e "arirang_post_fs_data" "no usable vendor bind target found"
        rm -f "$LANDING_BINDPATH"
        return 1
    fi

    target_identity=$(stat -c '%d:%i' "$bind_target" 2>/dev/null) || return 1

    # Repeat every mutable predicate immediately before mount(2). The vendor
    # partition is normally immutable, but this also fails closed on devices
    # with overlays or another root module changing the selected path.
    if ! arirang_vendor_target_allowed "$bind_target" ||
        ! arirang_is_root_regular_file "$bind_target" ||
        arirang_is_mountpoint "$bind_target" ||
        arirang_vendor_target_is_mapped "$bind_target" ||
        arirang_vendor_target_is_referenced "$bind_target"; then
        arirang_log e "arirang_post_fs_data" "vendor bind target changed before mount"
        return 1
    fi
    current_identity=$(stat -c '%d:%i' "$bind_target" 2>/dev/null) || return 1
    if [ "$current_identity" != "$target_identity" ]; then
        arirang_log e "arirang_post_fs_data" "vendor bind target inode changed before mount"
        return 1
    fi

    if ! mount --bind "$LANDING_HOOK" "$bind_target" 2>/dev/null; then
        arirang_log e "arirang_post_fs_data" "bind mount failed onto $bind_target"
        rm -f "$LANDING_BINDPATH"
        return 1
    fi

    # Do not trust mount(8)'s exit status alone: verify the target resolves to
    # the exact staged inode before persisting it for service.sh.
    if ! arirang_is_mountpoint "$bind_target" ||
        ! arirang_same_file "$LANDING_HOOK" "$bind_target" ||
        ! arirang_vendor_write_bind_record "$bind_target"; then
        umount "$bind_target" 2>/dev/null || :
        rm -f "$LANDING_BINDPATH"
        arirang_log e "arirang_post_fs_data" "bind mount verification failed"
        return 1
    fi

    arirang_log i "arirang_post_fs_data" "hook .so bind-mounted at $bind_target"
    return 0
}
