#!/system/bin/sh
# Arirang submodule - verified late-boot Widevine HAL injection.
# Never selects or modifies a third-party application process.
#
# KernelSU/Magisk may kill long-running late_start service scripts. The
# foreground entry point only validates staging + acquires a lock, then forks a
# detached worker that waits for the Widevine HAL and performs injection.

set -u
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" != "$0" ] || SCRIPT_DIR="."
MODDIR=$(CDPATH= cd "$SCRIPT_DIR" 2>/dev/null && pwd -P) || {
    log -p e -t arirang_service "cannot resolve module directory"
    exit 1
}
. "$MODDIR/lib/common.sh"

LOG_TAG="arirang_service"
PIDOF=""
SERVICE_LOCK="$LANDING_DIR/service.lock"
WORKER_PID_FILE="$LANDING_DIR/service.worker.pid"
INJECTOR_LOG="$LANDING_DIR/.injector.$$.log"
SERVICE_LOCK_HELD=false
IS_WORKER=false

arirang_service_cleanup() {
    rm -f "$INJECTOR_LOG" 2>/dev/null || :
    # Only the worker owns the lock for the full wait/inject window. The
    # foreground parent releases nothing on exit after a successful fork.
    if [ "$IS_WORKER" = true ] && [ "$SERVICE_LOCK_HELD" = true ]; then
        rmdir "$SERVICE_LOCK" 2>/dev/null || :
        SERVICE_LOCK_HELD=false
        rm -f "$WORKER_PID_FILE" 2>/dev/null || :
    fi
}
trap arirang_service_cleanup 0
trap 'exit 129' 1
trap 'exit 130' 2
trap 'exit 143' 15

arirang_read_verified_bind_target() {
    local bind_target record_size
    arirang_is_root_dir_with_mode "$LANDING_DIR" 750 || return 1
    arirang_has_data_context "$LANDING_DIR" || return 1
    arirang_is_root_file_with_mode "$LANDING_BINDPATH" 600 || return 1
    arirang_has_data_context "$LANDING_BINDPATH" || return 1
    arirang_is_root_file_with_mode "$LANDING_HOOK" 640 || return 1
    arirang_has_hook_context "$LANDING_HOOK" || return 1

    record_size=$(stat -c '%s' "$LANDING_BINDPATH" 2>/dev/null) || return 1
    case "$record_size" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$record_size" -gt 0 ] && [ "$record_size" -le 256 ] || return 1

    bind_target=$(cat "$LANDING_BINDPATH" 2>/dev/null) || return 1
    arirang_config_value_is_safe "$bind_target" 256 || return 1
    arirang_vendor_target_allowed "$bind_target" || return 1
    arirang_is_root_file_with_mode "$bind_target" 640 || return 1
    arirang_has_hook_context "$bind_target" || return 1
    arirang_is_mountpoint "$bind_target" || return 1
    arirang_same_file "$LANDING_HOOK" "$bind_target" || return 1
    printf '%s' "$bind_target"
}

arirang_pid_start_time() {
    local pid="$1" stat_line stat_fields
    local IFS="$ARIRANG_WORD_IFS"
    stat_line=$(cat "/proc/$pid/stat" 2>/dev/null) || return 1
    stat_fields="${stat_line##*) }"
    set -- $stat_fields
    [ "$#" -ge 20 ] || return 1
    case "${20}" in
        ''|*[!0-9]*) return 1 ;;
    esac
    printf '%s' "${20}"
}

arirang_pid_real_uid() {
    local pid="$1" key real_uid remainder
    while IFS="$ARIRANG_WORD_IFS" read -r key real_uid remainder; do
        if [ "$key" = "Uid:" ]; then
            case "$real_uid" in
                ''|*[!0-9]*) return 1 ;;
            esac
            printf '%s' "$real_uid"
            return 0
        fi
    done < "/proc/$pid/status"
    return 1
}

arirang_pid_has_file_group() {
    local pid="$1" wanted_gid="$2" status_line gids gid
    local IFS="$ARIRANG_WORD_IFS"
    case "$wanted_gid" in
        ''|*[!0-9]*) return 1 ;;
    esac

    gids=""
    while IFS= read -r status_line; do
        case "$status_line" in
            Gid:*) gids="$gids ${status_line#Gid:}" ;;
            Groups:*) gids="$gids ${status_line#Groups:}" ;;
        esac
    done < "/proc/$pid/status" || return 1

    for gid in $gids; do
        case "$gid" in
            ''|*[!0-9]*) return 1 ;;
        esac
        [ "$gid" = "$wanted_gid" ] && return 0
    done
    return 1
}

arirang_maps_path_from_line() {
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

arirang_pid_maps_supported_widevine() {
    local pid="$1" map_line map_path
    local IFS="$ARIRANG_WORD_IFS"
    [ -r "/proc/$pid/maps" ] || return 1

    while IFS= read -r map_line; do
        map_path=$(arirang_maps_path_from_line "$map_line") || continue
        case "$map_path" in
            /vendor/lib64/libwvhidl.so | \
            /vendor/lib64/libwvaidl.so | \
            /vendor/lib64/mediadrm/libwvdrmengine.so | \
            /system/lib64/mediadrm/libwvdrmengine.so)
                return 0
                ;;
        esac
    done < "/proc/$pid/maps"
    return 1
}

arirang_pid_maps_path() {
    local pid="$1" wanted_path="$2" map_line map_path
    local IFS="$ARIRANG_WORD_IFS"
    [ -r "/proc/$pid/maps" ] || return 1
    while IFS= read -r map_line; do
        map_path=$(arirang_maps_path_from_line "$map_line") || continue
        [ "$map_path" = "$wanted_path" ] && return 0
    done < "/proc/$pid/maps"
    return 1
}

arirang_pid_matches_hal() {
    local pid="$1" expected_name="$2" uid exe exe_name cmdline argv0 hook_gid
    local start_before start_after
    local IFS="$ARIRANG_WORD_IFS"
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$pid" ] || return 1

    start_before=$(arirang_pid_start_time "$pid") || return 1

    uid=$(arirang_pid_real_uid "$pid") || return 1
    [ "$uid" -ge 1000 ] && [ "$uid" -lt 10000 ] || return 1

    exe=$(readlink "/proc/$pid/exe" 2>/dev/null) || return 1
    case "$exe" in
        *' (deleted)') return 1 ;;
        /vendor/bin/* | /system/bin/* | /apex/*/bin/*) ;;
        *) return 1 ;;
    esac
    exe_name=${exe##*/}
    cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null) || return 1
    argv0=${cmdline%% *}
    [ -n "$argv0" ] || return 1
    argv0=${argv0##*/}

    if [ "$exe_name" != "$expected_name" ] && [ "$argv0" != "$expected_name" ]; then
        return 1
    fi
    case "$expected_name" in
        *widevine* | *clearkey*) ;;
        *) return 1 ;;
    esac

    # ClearKey is only a valid fallback once it has actually loaded a Widevine
    # library the native hook understands. Named *widevine* daemons are trusted
    # by process identity alone: on some devices libwvhidl is demand-loaded.
    case "$expected_name" in
        *clearkey*)
            arirang_pid_maps_supported_widevine "$pid" || return 1
            ;;
    esac
    arirang_pid_maps_path "$pid" "$BIND_TARGET" && return 1

    hook_gid=$(arirang_file_gid "$LANDING_HOOK") || return 1
    arirang_pid_has_file_group "$pid" "$hook_gid" || return 1

    start_after=$(arirang_pid_start_time "$pid") || return 1
    [ "$start_before" = "$start_after" ]
}

arirang_find_unique_hal() {
    local hal_name raw_pids pid valid_pid valid_count start_time
    local IFS="$ARIRANG_WORD_IFS"
    for hal_name in \
        "android.hardware.drm@1.4-service.widevine" \
        "android.hardware.drm@1.3-service.widevine" \
        "vendor.drm-widevine-hal-1-4" \
        "android.hardware.drm@1.4-service.clearkey"; do
        arirang_validate_trusted_tool "$PIDOF" >/dev/null || return 1
        raw_pids=$("$PIDOF" "$hal_name" 2>/dev/null) || raw_pids=""
        valid_pid=""
        valid_count=0
        for pid in $raw_pids; do
            if arirang_pid_matches_hal "$pid" "$hal_name"; then
                valid_pid="$pid"
                valid_count=$((valid_count + 1))
            fi
        done
        if [ "$valid_count" -eq 1 ]; then
            start_time=$(arirang_pid_start_time "$valid_pid") || continue
            # Use ASCII RS rather than '|' — mksh treats '|' as pattern
            # alternation inside ${var%%|*}, which empties the field.
            printf '%s\036%s\036%s' "$valid_pid" "$hal_name" "$start_time"
            return 0
        fi
        if [ "$valid_count" -gt 1 ]; then
            log -p w -t "$LOG_TAG" "multiple matching HAL processes for $hal_name; waiting"
        fi
    done
    return 1
}

arirang_acquire_service_lock() {
    if mkdir "$SERVICE_LOCK" 2>/dev/null; then
        :
    elif arirang_is_root_dir_with_mode "$SERVICE_LOCK" 700 &&
        arirang_has_data_context "$SERVICE_LOCK" &&
        rmdir "$SERVICE_LOCK" 2>/dev/null &&
        mkdir "$SERVICE_LOCK" 2>/dev/null; then
        log -p w -t "$LOG_TAG" "reclaimed stale service lock"
    else
        # Another live worker still holds the lock.
        if [ -f "$WORKER_PID_FILE" ]; then
            local old_pid
            old_pid=$(cat "$WORKER_PID_FILE" 2>/dev/null) || old_pid=""
            case "$old_pid" in
                ''|*[!0-9]*) ;;
                *)
                    if [ -d "/proc/$old_pid" ]; then
                        log -p e -t "$LOG_TAG" "another injector worker is active pid=$old_pid"
                        return 1
                    fi
                    ;;
            esac
        fi
        rmdir "$SERVICE_LOCK" 2>/dev/null || :
        mkdir "$SERVICE_LOCK" 2>/dev/null || {
            log -p e -t "$LOG_TAG" "another injector service instance is active"
            return 1
        }
        log -p w -t "$LOG_TAG" "reclaimed orphaned service lock"
    fi
    SERVICE_LOCK_HELD=true
    chown 0:0 "$SERVICE_LOCK" 2>/dev/null || return 1
    chmod 0700 "$SERVICE_LOCK" || return 1
    chcon u:object_r:arirang_data_file:s0 "$SERVICE_LOCK" 2>/dev/null || return 1
    arirang_is_root_dir_with_mode "$SERVICE_LOCK" 700 || return 1
    arirang_has_data_context "$SERVICE_LOCK" || return 1
    return 0
}

arirang_worker_main() {
    local HAL_RESULT HAL_PID HAL_NAME HAL_START CURRENT_START CURRENT_BIND
    local INJECTOR_EXIT i
    IS_WORKER=true
    SERVICE_LOCK_HELD=true

    # Detach from the controlling terminal / parent service context so Magisk
    # and KernelSU do not tear us down when the foreground service script exits.
    if command -v setsid >/dev/null 2>&1; then
        :
    fi

    log -p i -t "$LOG_TAG" "worker started pid=$$ waiting for Widevine HAL"

    HAL_RESULT=""
    i=0
    while [ "$i" -lt 300 ]; do
        HAL_RESULT=$(arirang_find_unique_hal) || HAL_RESULT=""
        [ -n "$HAL_RESULT" ] && break
        # Periodic heartbeat so cold-boot triage can see the worker is alive.
        if [ $((i % 30)) -eq 0 ]; then
            log -p i -t "$LOG_TAG" "still waiting for Widevine HAL (${i}s)"
        fi
        sleep 1
        i=$((i + 1))
    done
    if [ -z "$HAL_RESULT" ]; then
        log -p e -t "$LOG_TAG" "Widevine HAL daemon not running or failed identity checks after ${i}s"
        exit 1
    fi

    HAL_PID=""
    HAL_NAME=""
    HAL_START=""
    {
        IFS=$(printf '\036')
        # shellcheck disable=SC2162
        read -r HAL_PID HAL_NAME HAL_START _ || true
    } <<ARIRANG_HAL_EOF
$HAL_RESULT
ARIRANG_HAL_EOF
    if [ -z "$HAL_PID" ] || [ -z "$HAL_NAME" ] || [ -z "$HAL_START" ]; then
        log -p e -t "$LOG_TAG" "invalid HAL discovery result"
        exit 1
    fi
    case "$HAL_PID" in ''|*[!0-9]*) log -p e -t "$LOG_TAG" "invalid HAL pid"; exit 1 ;; esac
    case "$HAL_START" in ''|*[!0-9]*) log -p e -t "$LOG_TAG" "invalid HAL start time"; exit 1 ;; esac

    CURRENT_START=$(arirang_pid_start_time "$HAL_PID") || CURRENT_START=""
    CURRENT_BIND=$(arirang_read_verified_bind_target) || CURRENT_BIND=""
    if [ "$CURRENT_START" != "$HAL_START" ] ||
        [ "$CURRENT_BIND" != "$BIND_TARGET" ] ||
        ! arirang_pid_matches_hal "$HAL_PID" "$HAL_NAME"; then
        log -p e -t "$LOG_TAG" "HAL or bind target changed before injection"
        exit 1
    fi

    rm -f "$INJECTOR_LOG" || exit 1
    : > "$INJECTOR_LOG" || exit 1
    chown 0:0 "$INJECTOR_LOG" 2>/dev/null || exit 1
    chmod 0600 "$INJECTOR_LOG" || exit 1
    chcon u:object_r:arirang_data_file:s0 "$INJECTOR_LOG" 2>/dev/null || exit 1
    if ! arirang_is_root_file_with_mode "$INJECTOR_LOG" 600 ||
        ! arirang_has_data_context "$INJECTOR_LOG"; then
        log -p e -t "$LOG_TAG" "injector log failed ownership, mode, or label checks"
        exit 1
    fi

    log -p i -t "$LOG_TAG" "injecting $BIND_TARGET into verified pid=$HAL_PID after ${i}s"
    (
        ulimit -f 2048 || exit 1
        "$INJECTOR" "$HAL_PID" "$BIND_TARGET" "$HAL_START"
    ) > "$INJECTOR_LOG" 2>&1
    INJECTOR_EXIT=$?

    while IFS= read -r line || [ -n "$line" ]; do
        log -p i -t "$LOG_TAG" "$line"
    done < "$INJECTOR_LOG"

    log -p i -t "$LOG_TAG" "injector exit=$INJECTOR_EXIT"
    # Explicit cleanup: some Android shells do not reliably run EXIT traps for
    # background subshells after a successful inject.
    rmdir "$SERVICE_LOCK" 2>/dev/null || :
    SERVICE_LOCK_HELD=false
    rm -f "$WORKER_PID_FILE" 2>/dev/null || :
    exit "$INJECTOR_EXIT"
}

# ----- foreground entry ---------------------------------------------------
if ! ensure_injector; then
    log -p e -t "$LOG_TAG" "injector failed ownership or file checks"
    exit 1
fi
if ! arirang_is_root_dir_with_mode "$LANDING_DIR" 750 ||
    ! arirang_has_data_context "$LANDING_DIR"; then
    log -p e -t "$LOG_TAG" "staging directory failed ownership, mode, or label checks"
    exit 1
fi
PIDOF=$(arirang_find_trusted_tool pidof) || PIDOF=""
if [ -z "$PIDOF" ]; then
    log -p e -t "$LOG_TAG" "trusted pidof executable not found"
    exit 1
fi

i=0
BIND_TARGET=""
while [ "$i" -lt 20 ]; do
    BIND_TARGET=$(arirang_read_verified_bind_target) || BIND_TARGET=""
    [ -n "$BIND_TARGET" ] && break
    sleep 0.5
    i=$((i + 1))
done
if [ -z "$BIND_TARGET" ]; then
    log -p e -t "$LOG_TAG" "bind path missing or failed verification; aborting injection"
    exit 1
fi

# If the hook is already mapped into the live Widevine HAL, skip re-injection.
raw_check=$("$PIDOF" "android.hardware.drm@1.4-service.widevine" 2>/dev/null) || raw_check=""
if [ -n "$raw_check" ]; then
    already=0
    for pid in $raw_check; do
        if arirang_pid_maps_path "$pid" "$BIND_TARGET"; then
            already=1
        fi
    done
    if [ "$already" -eq 1 ]; then
        log -p i -t "$LOG_TAG" "hook already mapped in Widevine HAL; nothing to do"
        exit 0
    fi
fi

if ! arirang_acquire_service_lock; then
    exit 1
fi

# Fork a detached worker and return immediately so the root manager does not
# kill a multi-minute wait. The worker inherits the lock directory. Do not
# redirect the worker's stdout/stderr: diagnostics go through `log` (logcat),
# and swallowing them also hides fatal shell parse/runtime failures.
(
    # Close stdin so the worker is not tied to the service runner's pipe.
    exec </dev/null
    arirang_worker_main
) &
WORKER_PID=$!
printf '%s' "$WORKER_PID" > "$WORKER_PID_FILE" 2>/dev/null || :
chown 0:0 "$WORKER_PID_FILE" 2>/dev/null || :
chmod 0600 "$WORKER_PID_FILE" 2>/dev/null || :
chcon u:object_r:arirang_data_file:s0 "$WORKER_PID_FILE" 2>/dev/null || :

# Parent must NOT release the lock; the worker owns it. Clear the flag so the
# parent's EXIT trap does not rmdir the lock out from under the child.
SERVICE_LOCK_HELD=false
log -p i -t "$LOG_TAG" "spawned injector worker pid=$WORKER_PID"
exit 0
