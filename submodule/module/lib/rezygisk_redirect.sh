#!/system/bin/sh
# Arirang submodule - ReZygisk injection-library path redirect.
#
# ReZygisk's ptracer (loader/src/ptracer/ptracer.c:184) hardcodes
# /data/adb/modules/rezygisk/lib{,64}/libzygisk.so, remote-openat's that path
# in every forked child and maps it FILE-BACKED - so the path string lands
# permanently in each injected process's /proc/<pid>/maps. Root detectors
# read their own maps and match exactly such prefixes ("/data/adb",
# "zygisk", "rezygisk").
#
# Fix without touching any ReZygisk code or configuration: keep the loader
# bytes under needle-free paths (/data/system/librz_inj{32,64}.so) and turn
# the canonical paths into symlinks to them. The kernel records the resolved
# target in maps, so the rendered path carries no flaggable substring.
#
# Idempotent: a ReZygisk update replaces the symlink with a fresh regular
# file; the next boot refreshes the clean copy and re-applies the redirect.
# Nothing here touches any third-party application process.

RZ_LIB_REDIRECT_LOG="arirang_post_fs_data"

arirang_rezygisk_lib_redirect_one() {
    local src="$1" dst="$2"

    [ -e "$src" ] || return 0

    # Fresh ReZygisk install/update: regular file landed on the canonical
    # path. Refresh the clean copy from it before re-applying the symlink.
    if [ -f "$src" ] && [ ! -L "$src" ]; then
        cat "$src" > "$dst" 2>/dev/null || return 1
        chown root:root "$dst" 2>/dev/null
        chmod 0644 "$dst" 2>/dev/null
        chcon u:object_r:arirang_rtlib_file:s0 "$dst" 2>/dev/null || {
            rm -f "$dst"
            return 1
        }
    fi

    [ -f "$dst" ] || return 1

    if [ ! -L "$src" ] || [ "$(readlink "$src" 2>/dev/null)" != "$dst" ]; then
        ln -sf "$dst" "$src" 2>/dev/null || return 1
    fi
    return 0
}

arirang_rezygisk_lib_redirect() {
    local ok=true

    if ! arirang_rezygisk_lib_redirect_one \
        "/data/adb/modules/rezygisk/lib64/libzygisk.so" \
        "/data/system/librz_inj64.so"; then
        ok=false
    fi

    # 32-bit forks inject through the same hardcoded prefix (LP_SELECT "").
    if ! arirang_rezygisk_lib_redirect_one \
        "/data/adb/modules/rezygisk/lib/libzygisk.so" \
        "/data/system/librz_inj32.so"; then
        ok=false
    fi

    # Every Zygisk module's library gets the same treatment: zygiskd opens
    # /data/adb/modules/<id>/zygisk/<arch>.so and holds that fd for the boot
    # lifetime (its /proc/<pid>/fd link renders the canonical path), file-backs
    # it into each injected child's maps, and passes the same path string to
    # code inside the zygote. Redirecting the path to a needle-free symlink
    # target fixes all three channels without touching any module's code.
    local moddir arch modname dst
    for moddir in /data/adb/modules/*/zygisk; do
        [ -d "$moddir" ] || continue
        case "$moddir" in */rezygisk/zygisk|*/arirang-submodule/zygisk) continue ;; esac
        modname=${moddir#/data/adb/modules/}
        modname=${modname%/zygisk}
        for arch in arm64-v8a armeabi-v7a; do
            [ -e "$moddir/$arch.so" ] || continue
            # Module ids are [a-zA-Z0-9._-]; strip anything else so a crafted
            # directory name cannot escape the destination prefix.
            case "$modname" in
                *[!a-zA-Z0-9._-]*) modname=$(printf '%s' "$modname" | tr -cd 'a-zA-Z0-9._-') ;;
            esac
            [ -n "$modname" ] || continue
            if ! arirang_rezygisk_lib_redirect_one \
                "$moddir/$arch.so" \
                "/data/system/librz_mod_${modname}_${arch}.so"; then
                ok=false
            fi
        done
    done

    $ok && arirang_log i "$RZ_LIB_REDIRECT_LOG" "rezygisk injection libs redirected" ||
        arirang_log w "$RZ_LIB_REDIRECT_LOG" "rezygisk redirect incomplete (rezygisk absent?)"

    return 0
}
