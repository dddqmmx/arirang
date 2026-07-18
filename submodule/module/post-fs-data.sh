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

exit 0
