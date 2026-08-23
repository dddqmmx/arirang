#!/system/bin/sh
# Arirang submodule - post-fs-data entry point.
# Runs in root mount namespace once /data is up, before zygote.
# All substantive work is delegated to focused library modules.
# Nothing in this script touches any third-party application process.

set -u
umask 077
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH

SCRIPT_DIR="${0%/*}"
[ "$SCRIPT_DIR" != "$0" ] || SCRIPT_DIR="."
MODDIR=$(CDPATH= cd "$SCRIPT_DIR" 2>/dev/null && pwd -P) || {
    log -p e -t arirang_post_fs_data "cannot resolve module directory"
    exit 1
}

# Source library modules (order matters: common.sh goes first).
. "$MODDIR/lib/common.sh"
. "$MODDIR/lib/staging.sh"
. "$MODDIR/lib/vendor_bind.sh"
. "$MODDIR/lib/widevine.sh"
. "$MODDIR/lib/resetprop.sh"
. "$MODDIR/lib/zygisk_hide.sh"
. "$MODDIR/lib/rezygisk_redirect.sh"

if ! arirang_common_init; then
    arirang_log e "arirang_post_fs_data" "runtime initialization failed"
    exit 1
fi

# ----- phase 1: stage hook .so into tmpfs ---------------------------------
if ! arirang_staging_setup; then
    arirang_log e "arirang_post_fs_data" "staging failed; exiting"
    exit 1
fi

# ----- phase 2: bind-mount hook into a vendor-linker-visible path ----------
if ! arirang_vendor_bind; then
    arirang_log e "arirang_post_fs_data" "vendor bind phase failed"
fi

# ----- phase 3: extract widevineDrmId for the DRM hook daemon --------------
if ! arirang_widevine_extract; then
    arirang_log e "arirang_post_fs_data" "widevine staging phase failed"
fi

# ----- phase 4: apply system property spoofing -----------------------------
if [ -n "$ARIRANG_CONFIG_PATH" ]; then
    if ! arirang_resetprop_apply; then
        arirang_log e "arirang_post_fs_data" "resetprop phase failed"
    fi
fi

# ----- phase 5: conceal the zygisk module mapping ---------------------------
# Must run before zygote loads Zygisk modules so the resolved module path
# recorded in process maps is the benign staging path instead of the
# canonical /data/adb/modules/.../zygisk path.
if ! arirang_zygisk_hide; then
    arirang_log e "arirang_post_fs_data" "zygisk concealment phase failed"
fi

# ----- phase 6: redirect the ReZygisk injection library path ----------------
# ReZygisk's ptracer file-backs its injection lib from a hardcoded
# /data/adb/modules/rezygisk/... path in every fork it injects, leaving that
# string in each child's maps for detectors to match. Redirecting the path to
# a needle-free symlink target (no ReZygisk code touched) removes the marker.
# Must run before zygote starts so rezygiskd's own module loads resolve
# through the redirected path too.
if ! arirang_rezygisk_lib_redirect; then
    arirang_log e "arirang_post_fs_data" "rezygisk redirect phase failed"
fi

exit 0
