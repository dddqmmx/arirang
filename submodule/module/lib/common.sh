# Arirang submodule - shared constants and security helpers.
# Source this from post-fs-data.sh and service.sh after setting MODDIR.

if [ -z "${MODDIR:-}" ]; then
    MODDIR="${0%/*}"
fi

INJECTOR="$MODDIR/bin/arirang_injector"
LIBDIR="$MODDIR/lib"

# ---------- staging paths ------------------------------------------------
LANDING_DIR="/dev/.arirang"
LANDING_HOOK="$LANDING_DIR/libarirang_drm_hook.so"
LANDING_ID="$LANDING_DIR/widevine_id"
LANDING_BINDPATH="$LANDING_DIR/bind_path"

# ---------- app config paths ---------------------------------------------
APP_ID="asia.nana7mi.arirang"
CONFIG_DIR_NAME="arirang-submodule"
CONFIG_FILE_NAME="config.json"
ARIRANG_CONFIG_PATH=""

# Build the POSIX default field separators without embedding trailing literal
# whitespace in source lines. The sentinel preserves the final newline through
# command substitution, which otherwise strips it.
ARIRANG_WORD_IFS=$(printf ' \t\n_')
ARIRANG_WORD_IFS=${ARIRANG_WORD_IFS%_}

# Only these immutable vendor paths may ever be covered by the hook bind
# mount. Keep this list centralized because service.sh validates the recorded
# path independently before invoking the injector.
ARIRANG_VENDOR_CANDIDATES="
/vendor/lib64/android.hidl.token@1.0-utils.so
/vendor/lib64/android.hidl.token@1.0.so
/vendor/lib64/android.frameworks.cameraservice.common-V1-ndk.so
/vendor/lib64/android.frameworks.cameraservice.device-V3-ndk.so
/vendor/lib64/libQ6MSFR_manager_stub.so
"

# ---------- logging ------------------------------------------------------
arirang_log() {
    local priority="$1" tag="$2" msg="$3"
    log -p "$priority" -t "$tag" "$msg"
}

# ---------- file/path validation -----------------------------------------
arirang_file_uid() {
    stat -c '%u' "$1" 2>/dev/null
}

arirang_file_gid() {
    stat -c '%g' "$1" 2>/dev/null
}

arirang_file_mode() {
    stat -c '%A' "$1" 2>/dev/null
}

arirang_file_mode_bits() {
    local mode
    mode=$(stat -c '%a' "$1" 2>/dev/null) || return 1
    case "$mode" in
        0[0-7][0-7][0-7]) mode=${mode#0} ;;
        [0-7][0-7][0-7]) ;;
        *) return 1 ;;
    esac
    printf '%s' "$mode"
}

arirang_mode_is_private_write() {
    local mode
    mode=$(arirang_file_mode "$1") || return 1

    case "$mode" in
        ??????????) ;;
        *) return 1 ;;
    esac

    # Reject group- or world-writable files. The symbolic stat form avoids
    # shell-specific octal parsing on Android's different /system/bin/sh
    # implementations.
    case "$mode" in
        ?????w????|????????w?) return 1 ;;
    esac
    return 0
}

arirang_is_root_regular_file() {
    [ -f "$1" ] &&
        [ ! -L "$1" ] &&
        [ "$(arirang_file_uid "$1")" = "0" ] &&
        arirang_mode_is_private_write "$1"
}

arirang_is_root_directory() {
    [ -d "$1" ] &&
        [ ! -L "$1" ] &&
        [ "$(arirang_file_uid "$1")" = "0" ] &&
        arirang_mode_is_private_write "$1"
}

arirang_is_root_file_with_mode() {
    local path="$1" expected_mode="$2" mode
    arirang_is_root_regular_file "$path" || return 1
    mode=$(arirang_file_mode_bits "$path") || return 1
    [ "$mode" = "$expected_mode" ]
}

arirang_is_root_dir_with_mode() {
    local path="$1" expected_mode="$2" mode
    arirang_is_root_directory "$path" || return 1
    mode=$(arirang_file_mode_bits "$path") || return 1
    [ "$mode" = "$expected_mode" ]
}

arirang_has_file_context() {
    local path="$1" expected_type="$2" context_line
    case "$expected_type" in
        arirang_hook_file | arirang_data_file) ;;
        *) return 1 ;;
    esac
    context_line=$(ls -dZ "$path" 2>/dev/null) || return 1
    case "$context_line" in
        *"u:object_r:${expected_type}:s0"*) return 0 ;;
    esac
    return 1
}

arirang_has_hook_context() {
    arirang_has_file_context "$1" arirang_hook_file
}

arirang_has_data_context() {
    arirang_has_file_context "$1" arirang_data_file
}

arirang_resolve_trusted_vendor_file() {
    local candidate="$1" canonical
    case "$candidate" in
        /vendor/*) ;;
        *) return 1 ;;
    esac
    canonical=$(readlink -f "$candidate" 2>/dev/null) || return 1
    case "$canonical" in
        /vendor/*) ;;
        *) return 1 ;;
    esac
    arirang_is_root_regular_file "$canonical" || return 1
    printf '%s' "$canonical"
}

# Files under /data/adb are writable storage, so validating only the final
# executable is insufficient: a less-privileged writer on any parent could
# replace it after validation. System partition paths are protected by their
# read-only mounts; root-manager paths get explicit parent checks here.
arirang_trusted_tool_parents_are_secure() {
    local path="$1" parent
    case "$path" in
        /data/adb/magisk/*)
            for parent in /data/adb /data/adb/magisk; do
                arirang_is_root_directory "$parent" || return 1
            done
            ;;
        /data/adb/ksu/bin/*)
            for parent in /data/adb /data/adb/ksu /data/adb/ksu/bin; do
                arirang_is_root_directory "$parent" || return 1
            done
            ;;
        /data/adb/ap/bin/*)
            for parent in /data/adb /data/adb/ap /data/adb/ap/bin; do
                arirang_is_root_directory "$parent" || return 1
            done
            ;;
        # KernelSU/APatch ship multicall binaries at the adb root; resetprop is
        # only a symlink (resetprop -> /data/adb/ksud). Validate the parent of
        # that real binary, not just the symlink path under bin/.
        /data/adb/ksud | /data/adb/apd)
            arirang_is_root_directory /data/adb || return 1
            ;;
    esac
    return 0
}

# Resolve a command from PATH only when its real executable is root-owned,
# non-writable by less-privileged users, and stored in a root-manager or
# read-only system directory. The original path is returned so multicall
# symlinks such as resetprop -> ksud/magisk retain their argv[0] behavior.
arirang_validate_trusted_tool() {
    local candidate="$1" canonical
    case "$candidate" in
        /data/adb/magisk/* | /data/adb/ksu/* | /data/adb/ap/* | \
        /data/adb/ksud | /data/adb/apd | \
        /debug_ramdisk/* | /sbin/* | /system/bin/* | /system/xbin/*)
            ;;
        *) return 1 ;;
    esac

    canonical=$(readlink -f "$candidate" 2>/dev/null) || return 1
    case "$canonical" in
        /data/adb/magisk/* | /data/adb/ksu/* | /data/adb/ap/* | \
        /data/adb/ksud | /data/adb/apd | \
        /debug_ramdisk/* | /sbin/* | /system/bin/* | /system/xbin/*)
            ;;
        *) return 1 ;;
    esac
    arirang_trusted_tool_parents_are_secure "$candidate" || return 1
    arirang_trusted_tool_parents_are_secure "$canonical" || return 1
    arirang_is_root_regular_file "$canonical" || return 1
    [ -x "$canonical" ] || return 1
    printf '%s' "$candidate"
}

arirang_find_trusted_tool() {
    local name="$1" candidate

    # Prefer absolute candidates over PATH so late-start service scripts still
    # resolve tools when Magisk/KSU inherit a stripped environment.
    case "$name" in
        resetprop)
            for candidate in \
                /data/adb/magisk/resetprop \
                /data/adb/ksu/bin/resetprop \
                /data/adb/ap/bin/resetprop \
                /debug_ramdisk/resetprop \
                /sbin/resetprop \
                /system/bin/resetprop \
                /system/xbin/resetprop; do
                [ -e "$candidate" ] || [ -L "$candidate" ] || continue
                if arirang_validate_trusted_tool "$candidate"; then
                    return 0
                fi
            done
            ;;
        pidof)
            for candidate in \
                /system/bin/pidof \
                /system/xbin/pidof \
                /vendor/bin/pidof; do
                [ -e "$candidate" ] || [ -L "$candidate" ] || continue
                if arirang_validate_trusted_tool "$candidate"; then
                    return 0
                fi
            done
            ;;
    esac

    candidate=$(command -v "$name" 2>/dev/null) || return 1
    arirang_validate_trusted_tool "$candidate"
}

arirang_vendor_target_allowed() {
    case "$1" in
        /vendor/lib64/android.hidl.token@1.0-utils.so | \
        /vendor/lib64/android.hidl.token@1.0.so | \
        /vendor/lib64/android.frameworks.cameraservice.common-V1-ndk.so | \
        /vendor/lib64/android.frameworks.cameraservice.device-V3-ndk.so | \
        /vendor/lib64/libQ6MSFR_manager_stub.so)
            return 0
            ;;
    esac
    return 1
}

arirang_is_mountpoint() {
    local wanted="$1" mountpoint
    [ -r /proc/self/mountinfo ] || return 1

    while IFS="$ARIRANG_WORD_IFS" read -r _ _ _ _ mountpoint _; do
        [ "$mountpoint" = "$wanted" ] && return 0
    done < /proc/self/mountinfo
    return 1
}

arirang_same_file() {
    local first second
    first=$(stat -c '%d:%i' "$1" 2>/dev/null) || return 1
    second=$(stat -c '%d:%i' "$2" 2>/dev/null) || return 1
    [ "$first" = "$second" ]
}

# Reject control characters and cap data copied from the manager-controlled
# JSON into root operations. UTF-8 display values remain supported.
arirang_config_value_is_safe() {
    local value="$1" max_bytes="$2" byte_count grep_status
    local IFS="$ARIRANG_WORD_IFS"

    case "$value" in
        *'
'*) return 1 ;;
    esac
    LC_ALL=C printf '%s' "$value" | grep -q '[[:cntrl:]]'
    grep_status=$?
    case "$grep_status" in
        0) return 1 ;;
        1) ;;
        *) return 1 ;;
    esac

    # A here-document adds exactly one byte after the already newline-free
    # value. This avoids a multi-command counting pipeline on Android shells.
    byte_count=$(LC_ALL=C wc -c <<ARIRANG_VALUE_EOF
$value
ARIRANG_VALUE_EOF
    ) || return 1
    set -- $byte_count
    [ "$#" -eq 1 ] || return 1
    byte_count="$1"
    case "$byte_count" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$byte_count" -ge 1 ] || return 1
    byte_count=$((byte_count - 1))
    [ "$byte_count" -le "$max_bytes" ] || return 1
    return 0
}

# ---------- config.json helper -------------------------------------------
# Read a single key from the app's config.json via the native injector binary.
get_config_val() {
    [ -n "$ARIRANG_CONFIG_PATH" ] || return 1
    "$INJECTOR" config "$ARIRANG_CONFIG_PATH" "$1" 2>/dev/null
}

get_safe_config_val() {
    local key="$1" max_bytes="$2" value
    value=$(get_config_val "$key") || return 1
    if ! arirang_config_value_is_safe "$value" "$max_bytes"; then
        arirang_log w "arirang_post_fs_data" "ignoring invalid config value for $key"
        return 1
    fi
    printf '%s' "$value"
}

# Resolve the manager config path (device-encrypted storage first). The file
# must be a regular, non-symlink file owned by the same application UID as its
# package data directory. Native config reads repeat these checks atomically.
resolve_config_path() {
    local base app_dir files_dir config_dir candidate candidate_parent
    local app_uid path_uid config_size
    ARIRANG_CONFIG_PATH=""

    for base in /data/user_de/0 /data/user/0; do
        app_dir="$base/$APP_ID"
        files_dir="$app_dir/files"
        config_dir="$files_dir/$CONFIG_DIR_NAME"
        candidate="$config_dir/$CONFIG_FILE_NAME"

        [ -d "$app_dir" ] || continue
        [ ! -L "$app_dir" ] || continue
        [ -d "$files_dir" ] || continue
        [ ! -L "$files_dir" ] || continue
        [ -d "$config_dir" ] || continue
        [ ! -L "$config_dir" ] || continue
        [ -f "$candidate" ] || continue
        [ ! -L "$candidate" ] || continue
        arirang_mode_is_private_write "$candidate" || continue

        app_uid=$(arirang_file_uid "$app_dir") || continue
        case "$app_uid" in
            ''|*[!0-9]*) continue ;;
        esac
        [ "$app_uid" -ge 10000 ] 2>/dev/null || continue

        path_uid=""
        for candidate_parent in "$files_dir" "$config_dir" "$candidate"; do
            path_uid=$(arirang_file_uid "$candidate_parent") || break
            case "$path_uid" in
                ''|*[!0-9]*) break ;;
            esac
            [ "$path_uid" = "$app_uid" ] || break
        done
        [ "${path_uid:-}" = "$app_uid" ] || continue

        config_size=$(stat -c '%s' "$candidate" 2>/dev/null) || continue
        case "$config_size" in
            ''|*[!0-9]*) continue ;;
        esac
        [ "$config_size" -le 65536 ] || continue

        ARIRANG_CONFIG_PATH="$candidate"
        return 0
    done
    return 1
}

# Ensure the bundled native helper is a root-owned regular file before ever
# changing or executing it. This prevents a replaced module symlink from
# turning chmod/exec into a root primitive.
ensure_injector() {
    if ! arirang_is_root_regular_file "$INJECTOR"; then
        return 1
    fi
    if [ ! -x "$INJECTOR" ]; then
        chmod 0755 "$INJECTOR" 2>/dev/null || return 1
    fi
    arirang_is_root_regular_file "$INJECTOR" && [ -x "$INJECTOR" ]
}

arirang_common_init() {
    if ! ensure_injector; then
        arirang_log e "arirang_post_fs_data" "injector failed ownership or file checks"
        return 1
    fi
    resolve_config_path || :
    return 0
}
